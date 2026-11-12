package org.amalia.unifiedhome.v2

import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PairingPeer(
    val nodeId: String, val homeId: String, val name: String,
    val address: String, val port: Int, val open: Boolean,
)

data class PairingStatus(
    val open: Boolean = false, val code: String? = null,
    val pendingName: String? = null, val error: String? = null,
)

/** Local-human-only SAS pairing. Discovery is untrusted; compare both displayed codes before
 * approve(). getInvitation must be a thread-safe, nonblocking snapshot of current membership.
 * This class never persists membership and never publishes an invitation through its flows. */
class HomeV2Pairing(
    private val nodeId: String,
    private val name: String,
    private val scope: CoroutineScope,
    private val getInvitation: () -> String?,
) {
    private val lock = Any()
    private val lifecycle = Mutex()
    private val clientGate = Mutex()
    private val mutablePeers = MutableStateFlow<List<PairingPeer>>(emptyList())
    private val mutableStatus = MutableStateFlow(PairingStatus())
    val peers: StateFlow<List<PairingPeer>> = mutablePeers.asStateFlow()
    val status: StateFlow<PairingStatus> = mutableStatus.asStateFlow()
    private var runtime: Runtime? = null
    private var windowUntil = 0L
    private var windowInvitation: String? = null
    private var offer: Offer? = null
    private var offerCount = 0
    private var incoming: Incoming? = null
    private var outgoing: Outgoing? = null
    private var actionEpoch = 0L
    private val candidates = LinkedHashMap<String, Seen>()

    private class Runtime(val job: Job, val udp: DatagramSocket, val tcp: ServerSocket) {
        val scope = CoroutineScope(job + Dispatchers.IO)
        val sockets = HashSet<Socket>()
    }
    private data class Seen(val peer: PairingPeer, val time: Long)
    private class Offer(
        val session: String, val invitation: String, val pair: KeyPair, val deadline: Long,
    )
    private class Incoming(
        val session: String, val invitation: String, val key: ByteArray,
        val transcript: ByteArray, val deadline: Long,
        var delivered: JSONObject? = null, var deliveredUntil: Long = 0,
    )
    private class Outgoing(val peer: PairingPeer, val initialInvitation: String?, val deadline: Long) {
        var session: String? = null
        var key: ByteArray? = null
        var transcript: ByteArray? = null
        var socket: Socket? = null
    }

    init { v2Id(nodeId); printable(name) }

    suspend fun start(): Unit = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            synchronized(lock) { if (runtime != null) return@withLock }
            v2Require(scope.isActive, "pairing_stopped")
            var udp: DatagramSocket? = null
            var tcp: ServerSocket? = null
            try {
                udp = DatagramSocket(null)
                udp.apply {
                    reuseAddress = false; broadcast = true
                    bind(InetSocketAddress(ipv4("0.0.0.0"), PORT))
                }
                tcp = ServerSocket()
                tcp.apply {
                    reuseAddress = true; bind(InetSocketAddress(ipv4("0.0.0.0"), PORT), 8)
                }
                val r = Runtime(SupervisorJob(scope.coroutineContext[Job]), udp, tcp)
                synchronized(lock) { runtime = r; mutableStatus.value = PairingStatus() }
                // This child closes blocking I/O immediately on parent-scope cancellation.
                r.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { shutdown(r) }
                }
                r.scope.launch { receive(r) }
                r.scope.launch { accept(r) }
                r.scope.launch {
                    var nextBroadcast = 0L
                    while (isActive) {
                        try {
                            synchronized(lock) { if (runtime === r) maintain() }
                            if (now() >= nextBroadcast) {
                                broadcast(r); nextBroadcast = now() + 5000
                            }
                        } catch (_: Exception) {
                            synchronized(lock) { if (runtime === r) mutableStatus.value = mutableStatus.value.copy(error = "discovery_failed") }
                        }
                        delay(250)
                    }
                }
            } catch (e: Exception) {
                udp?.close(); tcp?.close()
                if (e is CancellationException) throw e
                synchronized(lock) { mutableStatus.value = PairingStatus(error = "start_failed") }
                throw HomeV2Exception("start_failed")
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            val r = synchronized(lock) { runtime } ?: return@withLock
            shutdown(r)
            r.job.cancelAndJoin()
        }
    }

    private fun shutdown(r: Runtime) = synchronized(lock) {
        if (runtime !== r) return@synchronized
        runtime = null
        clearPairing()
        candidates.clear(); mutablePeers.value = emptyList()
        r.udp.close(); r.tcp.close()
        r.sockets.toList().forEach { runCatching { it.close() } }
        r.sockets.clear()
    }

    /** Opens only on an already-joined host; runs callback/crypto off the caller's thread. */
    fun openWindow() = action {
        maintain()
        v2Require(offer == null && incoming == null && outgoing == null, "pairing_busy")
        val invitation = invitation() ?: throw HomeV2Exception("not_joined")
        windowInvitation = invitation; windowUntil = now() + WINDOW; offerCount = 0
        mutableStatus.value = PairingStatus(open = true)
    }

    /** Invoke only after the human compared the six digits on both devices. */
    fun approve() = action {
        maintain()
        val p = incoming ?: throw HomeV2Exception("no_pending_pairing")
        v2Require(p.delivered == null && windowUntil > now(), "pairing_closed")
        v2Require(invitation() == p.invitation, "membership_changed")
        val nonce = V2Wire.randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(p.key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(p.transcript)
        val delivery = JSONObject().put("op", "joined").put("nonce", b64(nonce))
            .put("ciphertext", b64(cipher.doFinal(p.invitation.toByteArray(Charsets.UTF_8))))
        v2Require(invitation() == p.invitation, "membership_changed")
        v2Require(windowUntil > now(), "pairing_closed")
        p.delivered = delivery
        p.deliveredUntil = now() + WINDOW
        windowUntil = 0; windowInvitation = null
        mutableStatus.value = PairingStatus()
    }

    fun reject() { cancel() }

    fun cancel() = synchronized(lock) { clearPairing() }

    private fun clearPairing(error: String? = null) {
        actionEpoch++
        windowUntil = 0; windowInvitation = null
        offer = null; offerCount = 0
        incoming?.key?.fill(0); incoming = null
        outgoing?.key?.fill(0); outgoing?.socket?.let { runCatching { it.close() } }; outgoing = null
        mutableStatus.value = PairingStatus(error = error)
    }

    private fun action(block: () -> Unit) {
        val (r, epoch) = synchronized(lock) { runtime to actionEpoch }
        if (r == null) {
            synchronized(lock) { mutableStatus.value = PairingStatus(error = "pairing_stopped") }
            return
        }
        r.scope.launch {
            synchronized(lock) {
                if (runtime !== r || actionEpoch != epoch) return@synchronized
                try { block() } catch (_: Exception) { clearPairing("pairing_unavailable") }
            }
        }
    }

    suspend fun begin(peer: PairingPeer): String = withContext(Dispatchers.IO) {
        clientGate.withLock {
            val r: Runtime
            val p: Outgoing
            synchronized(lock) {
                r = runtime ?: throw HomeV2Exception("pairing_stopped")
                maintain()
                v2Id(peer.nodeId); v2Id(peer.homeId); printable(peer.name); ipv4(peer.address)
                v2Require(peer.nodeId != nodeId && peer.port == PORT && peer.open, "invalid_peer")
                v2Require(outgoing == null && incoming == null && windowUntil == 0L, "pairing_busy")
                val initial = invitation()
                v2Require(initial == null, "already_joined")
                p = Outgoing(peer, initial, now() + WINDOW); outgoing = p
            }
            try {
                val offered = exchange(r, p, JSONObject().put("op", "offer"))
                remoteError(offered)
                v2Keys(offered, setOf("op", "session", "node", "home", "name", "commitment"))
                v2Require(v2String(offered, "op") == "offer", "invalid_response")
                val session = v2Id(v2String(offered, "session"))
                v2Require(v2String(offered, "node") == peer.nodeId && v2String(offered, "home") == peer.homeId, "peer_changed")
                printable(v2String(offered, "name"))
                val expectedCommitment = v2String(offered, "commitment")
                v2Require(expectedCommitment.matches(Regex("[0-9a-f]{64}")), "invalid_response")
                // Generate our key only after receiving the host's binding commitment.
                val pair = keyPair(); val public = b64(pair.public.encoded)
                val response = exchange(r, p, JSONObject().put("op", "hello").put("session", session)
                    .put("node", nodeId).put("name", name).put("public", public))
                remoteError(response)
                v2Keys(response, setOf("op", "session", "node", "home", "name", "public"))
                v2Require(v2String(response, "op") == "challenge", "invalid_response")
                v2Require(v2Id(v2String(response, "session")) == session, "invalid_session")
                v2Require(v2String(response, "node") == peer.nodeId && v2String(response, "home") == peer.homeId, "peer_changed")
                printable(v2String(response, "name"))
                val serverPublic = v2String(response, "public")
                v2Require(commitment(decode(serverPublic)) == expectedCommitment, "invalid_response")
                val transcript = transcript(session, peer.nodeId, nodeId, peer.homeId, serverPublic, public)
                val key = derive(pair, serverPublic, transcript)
                synchronized(lock) {
                    v2Require(runtime === r && outgoing === p && now() < p.deadline, "pairing_cancelled")
                    v2Require(invitation() == p.initialInvitation, "membership_changed")
                    p.session = session; p.key = key; p.transcript = transcript
                    sas(key).also { mutableStatus.value = PairingStatus(code = it, pendingName = peer.name) }
                }
            } catch (e: Exception) {
                synchronized(lock) { if (outgoing === p) clearPairing("pairing_failed") }
                if (e is CancellationException) throw e
                throw safeFailure(e)
            }
        }
    }

    /** Poll once; null means the host has not explicitly approved yet. */
    suspend fun finish(): String? = withContext(Dispatchers.IO) {
        clientGate.withLock {
            val r: Runtime
            val p: Outgoing
            synchronized(lock) {
                r = runtime ?: throw HomeV2Exception("pairing_stopped")
                maintain()
                p = outgoing ?: throw HomeV2Exception("no_pending_pairing")
                v2Require(p.session != null, "no_pending_pairing")
                v2Require(invitation() == p.initialInvitation, "membership_changed")
            }
            try {
                val response = exchange(r, p, JSONObject().put("op", "poll").put("session", p.session))
                remoteError(response)
                synchronized(lock) {
                    v2Require(runtime === r && outgoing === p && now() < p.deadline, "pairing_cancelled")
                    v2Require(invitation() == p.initialInvitation, "membership_changed")
                    if (v2String(response, "op") == "pending") {
                        v2Keys(response, setOf("op")); return@withLock null
                    }
                    v2Keys(response, setOf("op", "nonce", "ciphertext"))
                    v2Require(v2String(response, "op") == "joined", "invalid_response")
                    val nonce = decode(v2String(response, "nonce")); v2Require(nonce.size == 12)
                    val encrypted = decode(v2String(response, "ciphertext")); v2Require(encrypted.size >= 16)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(p.key!!, "AES"), GCMParameterSpec(128, nonce))
                    cipher.updateAAD(p.transcript!!)
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(cipher.doFinal(encrypted))).toString()
                    v2Require(home(text) == p.peer.homeId, "invalid_invitation")
                    v2Require(invitation() == p.initialInvitation, "membership_changed")
                    clearPairing()
                    text
                }
            } catch (e: Exception) {
                // Transport failures are retryable: the host retains the same encrypted delivery.
                if (e is CancellationException) {
                    synchronized(lock) { if (outgoing === p) clearPairing() }; throw e
                }
                if (e is HomeV2Exception) synchronized(lock) { if (outgoing === p) clearPairing(e.code) }
                throw safeFailure(e)
            }
        }
    }

    private fun maintain() {
        val time = now()
        if ((windowUntil != 0L && time >= windowUntil) ||
            (incoming?.let { time >= if (it.delivered == null) it.deadline else it.deliveredUntil } == true) ||
            (outgoing?.let { time >= it.deadline } == true)) clearPairing("pairing_expired")
        // An unused offer expires independently; the human-opened window stays open.
        if (offer?.let { time >= it.deadline } == true) offer = null
        val expected = incoming?.invitation ?: offer?.invitation ?: windowInvitation
        if (expected != null && invitation() != expected) clearPairing("membership_changed")
        if (outgoing?.let { invitation() != it.initialInvitation } == true) clearPairing("membership_changed")
        val iterator = candidates.entries.iterator()
        while (iterator.hasNext()) if (time - iterator.next().value.time >= 20000) iterator.remove()
        mutablePeers.value = candidates.values.map { it.peer }.filter { it.homeId.isNotEmpty() }
    }

    private fun announcement(): JSONObject = synchronized(lock) {
        maintain()
        JSONObject().put("v", 1).put("type", "home").put("node", nodeId)
            .put("home", invitation()?.let(::home) ?: "").put("name", name)
            .put("port", PORT).put("open", windowUntil > now() && offer == null && incoming == null && offerCount < MAX_OFFERS)
    }

    private fun broadcast(r: Runtime) {
        val addresses = linkedSetOf(ipv4("255.255.255.255"))
        val interfaces = NetworkInterface.getNetworkInterfaces()
        if (interfaces != null) for (network in interfaces) {
            if (network.isUp && !network.isLoopback) network.interfaceAddresses.forEach {
                (it.broadcast as? Inet4Address)?.let(addresses::add)
            }
        }
        val probe = JSONObject().put("v", 1).put("type", "probe")
        val announce = announcement()
        for (address in addresses) for (message in listOf(probe, announce)) {
            try { send(r, message, address, PORT) } catch (_: Exception) { /* Interface may disappear. */ }
        }
    }

    private fun send(r: Runtime, obj: JSONObject, address: InetAddress, port: Int) {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        v2Require(bytes.size <= MAX)
        r.udp.send(DatagramPacket(bytes, bytes.size, address, port))
    }

    private fun receive(r: Runtime) {
        val buffer = ByteArray(MAX + 1)
        var epoch = now(); var count = 0
        while (r.job.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size); r.udp.receive(packet)
                val time = now()
                if (time - epoch >= 1000) { epoch = time; count = 0 }
                if (++count > 20 || packet.length > MAX || packet.address !is Inet4Address ||
                    packet.address.isAnyLocalAddress || packet.address.isMulticastAddress) continue
                val obj = V2Json.parse(packet.data.copyOfRange(packet.offset, packet.offset + packet.length), MAX)
                v2Require(v2Int(obj, "v") == 1)
                when (v2String(obj, "type")) {
                    "probe" -> {
                        v2Keys(obj, setOf("v", "type"))
                        send(r, announcement(), packet.address, packet.port)
                    }
                    "home" -> {
                        v2Keys(obj, setOf("v", "type", "node", "home", "name", "port", "open"))
                        val node = v2Id(v2String(obj, "node")); if (node == nodeId) continue
                        val home = v2String(obj, "home"); if (home.isNotEmpty()) v2Id(home)
                        val peerName = printable(v2String(obj, "name"))
                        v2Require(v2Int(obj, "port") == PORT && obj.opt("open") is Boolean)
                        val open = obj.getBoolean("open"); v2Require(!open || home.isNotEmpty())
                        val peer = PairingPeer(node, home, peerName, packet.address.hostAddress!!, PORT, open)
                        synchronized(lock) {
                            if (runtime !== r) return
                            maintain()
                            if (node in candidates || candidates.size < 64) candidates[node] = Seen(peer, time)
                            mutablePeers.value = candidates.values.map { it.peer }.filter { it.homeId.isNotEmpty() }
                        }
                    }
                    else -> throw HomeV2Exception("invalid_request")
                }
            } catch (_: Exception) { if (r.udp.isClosed) return }
        }
    }

    private fun accept(r: Runtime) {
        while (r.job.isActive) {
            try {
                val socket = r.tcp.accept()
                val admitted = synchronized(lock) {
                    if (runtime !== r || r.sockets.size >= 8 || socket.inetAddress !is Inet4Address) false
                    else { r.sockets.add(socket); true }
                }
                if (!admitted) { socket.close(); continue }
                r.scope.launch {
                    val timeout = r.scope.launch { delay(5000); runCatching { socket.close() } }
                    try {
                        socket.soTimeout = 5000
                        val request = read(socket)
                        val response = synchronized(lock) {
                            v2Require(runtime === r, "pairing_stopped")
                            maintain()
                            when (v2String(request, "op")) {
                                "offer" -> offer(request)
                                "hello" -> hello(request)
                                "poll" -> poll(request)
                                else -> throw HomeV2Exception("invalid_request")
                            }
                        }
                        write(socket, response)
                    } catch (e: Exception) {
                        runCatching { write(socket, JSONObject().put("op", "error").put("code", wireCode(e))) }
                    } finally {
                        timeout.cancel(); runCatching { socket.close() }
                        synchronized(lock) { r.sockets.remove(socket) }
                    }
                }
            } catch (_: Exception) { if (r.tcp.isClosed) return }
        }
    }

    private fun offer(request: JSONObject): JSONObject {
        v2Keys(request, setOf("op"))
        v2Require(windowUntil > now() && windowInvitation != null, "pairing_closed")
        v2Require(offer == null && incoming == null && outgoing == null && offerCount < MAX_OFFERS, "pairing_busy")
        val invitation = invitation() ?: throw HomeV2Exception("not_joined")
        v2Require(invitation == windowInvitation, "membership_changed")
        val pair = keyPair()
        val p = Offer(V2Wire.randomHex(16), invitation, pair, minOf(now() + OFFER_TTL, windowUntil))
        v2Require(invitation() == invitation && now() < p.deadline, "membership_changed")
        offer = p; offerCount++
        return JSONObject().put("op", "offer").put("session", p.session).put("node", nodeId)
            .put("home", home(invitation)).put("name", name).put("commitment", commitment(pair.public.encoded))
    }

    private fun hello(request: JSONObject): JSONObject {
        v2Keys(request, setOf("op", "session", "node", "name", "public"))
        v2Require(windowUntil > now() && windowInvitation != null, "pairing_closed")
        v2Require(incoming == null && outgoing == null, "pairing_busy")
        val p = offer ?: throw HomeV2Exception("invalid_session")
        val session = v2Id(v2String(request, "session"))
        v2Require(MessageDigest.isEqual(session.toByteArray(Charsets.US_ASCII), p.session.toByteArray(Charsets.US_ASCII)), "invalid_session")
        v2Require(now() < p.deadline, "invalid_session")
        val client = v2Id(v2String(request, "node")); v2Require(client != nodeId)
        val clientName = printable(v2String(request, "name"))
        val clientPublic = v2String(request, "public")
        v2Require(invitation() == p.invitation && p.invitation == windowInvitation, "membership_changed")
        val public = b64(p.pair.public.encoded)
        val home = home(p.invitation)
        val transcript = transcript(session, nodeId, client, home, public, clientPublic)
        val key = derive(p.pair, clientPublic, transcript)
        try {
            v2Require(invitation() == p.invitation, "membership_changed")
            v2Require(now() < p.deadline, "invalid_session")
        } catch (e: Exception) {
            key.fill(0)
            throw e
        }
        // Consume only a valid hello: malformed requests cannot replace the committed key.
        incoming = Incoming(session, p.invitation, key, transcript, windowUntil)
        offer = null
        mutableStatus.value = PairingStatus(open = true, code = sas(key), pendingName = clientName)
        return JSONObject().put("op", "challenge").put("session", session).put("node", nodeId)
            .put("home", home).put("name", name).put("public", public)
    }

    private fun poll(request: JSONObject): JSONObject {
        v2Keys(request, setOf("op", "session"))
        val token = v2Id(v2String(request, "session"))
        val p = incoming ?: throw HomeV2Exception("invalid_session")
        v2Require(MessageDigest.isEqual(token.toByteArray(Charsets.US_ASCII), p.session.toByteArray(Charsets.US_ASCII)), "invalid_session")
        v2Require(invitation() == p.invitation, "membership_changed")
        return p.delivered ?: JSONObject().put("op", "pending")
    }

    private suspend fun exchange(r: Runtime, p: Outgoing, request: JSONObject): JSONObject {
        val socket = Socket()
        synchronized(lock) {
            v2Require(runtime === r && outgoing === p && now() < p.deadline, "pairing_cancelled")
            v2Require(invitation() == p.initialInvitation, "membership_changed")
            v2Require(r.sockets.size < 8, "pairing_busy")
            r.sockets.add(socket); p.socket = socket
        }
        val timeout = r.scope.launch { delay(5000); runCatching { socket.close() } }
        // Caller cancellation closes a blocking read/connect, not just the coroutine job.
        val cancellation = CoroutineScope(kotlin.coroutines.coroutineContext).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { socket.close() } }
        }
        try {
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(ipv4(p.peer.address), PORT), 5000)
            write(socket, request)
            return read(socket)
        } finally {
            cancellation.cancel(); timeout.cancel(); runCatching { socket.close() }
            synchronized(lock) { r.sockets.remove(socket); if (p.socket === socket) p.socket = null }
        }
    }

    private fun read(socket: Socket): JSONObject {
        val input = DataInputStream(socket.getInputStream())
        val size = input.readInt(); v2Require(size in 1..MAX, "invalid_request")
        return V2Json.parse(ByteArray(size).also(input::readFully), MAX)
    }
    private fun write(socket: Socket, obj: JSONObject) {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        v2Require(bytes.size in 1..MAX, "invalid_request")
        DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
    }
    private fun remoteError(obj: JSONObject) {
        if (v2String(obj, "op") != "error") return
        v2Keys(obj, setOf("op", "code"))
        val code = v2String(obj, "code")
        throw HomeV2Exception(if (code in WIRE_ERRORS) code else "pairing_failed")
    }
    private fun wireCode(e: Exception): String = (e as? HomeV2Exception)?.code?.takeIf { it in WIRE_ERRORS } ?: "invalid_request"
    private fun safeFailure(e: Exception): HomeV2Exception = if (e is HomeV2Exception) e else HomeV2Exception("pairing_failed")
    private fun invitation(): String? = getInvitation()?.also { home(it) }

    private companion object {
        const val PORT = 47656
        const val MAX = 4096
        const val WINDOW = 120000L
        const val OFFER_TTL = 20000L
        const val MAX_OFFERS = 10
        val WIRE_ERRORS = setOf("invalid_request", "pairing_closed", "pairing_busy", "invalid_session", "membership_changed", "not_joined", "pairing_stopped")
        val INVITATION = Regex("amalia-home:v3:([0-9a-f]{32}):[0-9a-f]{64}")
        fun now() = SystemClock.elapsedRealtime()
        fun home(text: String): String = INVITATION.matchEntire(text)?.groupValues?.get(1)
            ?: throw HomeV2Exception("invalid_invitation")
        fun printable(text: String): String {
            v2Require(text.length <= 80)
            var index = 0
            while (index < text.length) {
                val c = text[index++]
                if (c.isHighSurrogate()) {
                    v2Require(index < text.length && text[index].isLowSurrogate())
                    val point = Character.toCodePoint(c, text[index++])
                    v2Require(!Character.isISOControl(point) && Character.getType(point) != Character.FORMAT.toInt())
                } else v2Require(!c.isLowSurrogate() && !Character.isISOControl(c) &&
                    Character.getType(c) != Character.FORMAT.toInt() && Character.getType(c) != Character.UNASSIGNED.toInt() &&
                    Character.getType(c) != Character.LINE_SEPARATOR.toInt() && Character.getType(c) != Character.PARAGRAPH_SEPARATOR.toInt())
            }
            return text
        }
        fun ipv4(text: String): Inet4Address {
            val parts = text.split('.')
            v2Require(parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 &&
                p.all { it in '0'..'9' } && (p.length == 1 || p[0] != '0') && (p.toIntOrNull() ?: 256) <= 255 }, "invalid_peer")
            return InetAddress.getByAddress(ByteArray(4) { parts[it].toInt().toByte() }) as Inet4Address
        }
        fun commitment(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(text: String): ByteArray {
            v2Require(text.length <= MAX)
            val bytes = try { Base64.decode(text, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { throw HomeV2Exception("invalid_request") }
            v2Require(b64(bytes) == text)
            return bytes
        }
        fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        fun transcript(session: String, server: String, client: String, home: String, serverPublic: String, clientPublic: String): ByteArray =
            listOf("amalia-pair/v1", session, server, client, home, serverPublic, clientPublic).joinToString("\n").toByteArray(Charsets.UTF_8)
        fun derive(pair: KeyPair, public: String, transcript: ByteArray): ByteArray {
            val der = decode(public); v2Require(der.size in 80..256)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as? ECPublicKey
                ?: throw HomeV2Exception("invalid_request")
            // Compare the full domain parameters, not the algorithm name or field size alone.
            val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            val actual = key.params
            v2Require(actual.curve == expected.curve && actual.generator == expected.generator &&
                actual.order == expected.order && actual.cofactor == expected.cofactor)
            val prime = (actual.curve.field as? ECFieldFp)?.p ?: throw HomeV2Exception("invalid_request")
            val x = key.w.affineX; val y = key.w.affineY
            v2Require(x != null && y != null && x.signum() >= 0 && y.signum() >= 0 && x < prime && y < prime)
            v2Require(y.multiply(y).mod(prime) == x.multiply(x).multiply(x).add(actual.curve.a.multiply(x)).add(actual.curve.b).mod(prime))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(pair.private); agreement.doPhase(key, true)
            val secret = agreement.generateSecret()
            return try { MessageDigest.getInstance("SHA-256").digest(secret + transcript) } finally { secret.fill(0) }
        }
        fun sas(key: ByteArray): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(key + "sas".toByteArray(Charsets.US_ASCII))
            var value = 0L
            repeat(4) { value = (value shl 8) or (bytes[it].toLong() and 255) }
            return (value % 1000000).toString().padStart(6, '0')
        }
    }
}
