package org.amalia.unifiedhome

import android.content.Context
import android.net.wifi.WifiManager
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** Authenticated household member; host is a routing hint, never an identity. */
data class MeshNode(
    val id: String,
    val name: String,
    val role: String,
    val priority: Int,
    val host: String,
    val port: Int,
    val lastSeenAt: Long,
    val self: Boolean = false,
    val session: String = "",
    val platform: String = "android",
    val services: List<String> = emptyList(),
) {
    fun online(now: Long = System.currentTimeMillis()): Boolean =
        self || now - lastSeenAt <= MeshService.NODE_TTL_MS
}

data class VoteResult(
    val winnerId: String,
    val winnerName: String,
    val score: Double,
    val participants: Int,
    val mine: Boolean,
    val localOnly: Boolean = false,
)

/** Public settings deliberately contain neither household key nor invite. */
data class MeshConfig(
    val id: String = newId("node"),
    val name: String = "",
    val role: String = "hub",
    val priority: Int = 10,
    val port: Int = MeshService.DEFAULT_PORT,
)

class MeshService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRequest: suspend (operation: String, args: JSONObject, fromNode: String, fromSession: String) -> JSONObject,
) {
    companion object {
        const val DEFAULT_PORT = 47654
        const val NODE_TTL_MS = 15_000L
        const val VOTE_WINDOW_MS = 150L
        private const val ANNOUNCE_INTERVAL_MS = 5_000L
        private const val MAX_PEERS = 128
        private const val MAX_WORK = 16
        private const val MAX_HISTORY = 4096
        private const val REPLAY_TTL_MS = 120_000L
        private const val REQUEST_TIMEOUT_MS = 12_000L
    }

    private data class Pending(
        val peer: String, val peerSession: String, val operation: String,
        val result: CompletableDeferred<JSONObject>,
    )
    private data class RequestKey(val peer: String, val session: String, val request: String)
    private data class ReplayKey(val peer: String, val session: String, val nonce: String)

    private val file = AtomicFile(File(context.filesDir, "unified-home/mesh.json"))
    private var loaded = false
    private var storageHealthy = true
    private var config = MeshConfig(name = android.os.Build.MODEL?.takeIf { HomeLink.validText(it, 128) } ?: "Amalia")
    private var credentials: HomeLink.Credentials? = null
    private var session = HomeLink.uuid()
    private var requestedStart = false
    private var socket: DatagramSocket? = null
    private var lifecycle: Job? = null
    private var workers: CoroutineScope? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val peers = mutableMapOf<String, MeshNode>()
    private val pending = mutableMapOf<String, Pending>()
    private val replay = LinkedHashMap<ReplayKey, Long>()
    // Retained until our session ends, even when a peer goes offline. Never evict to admit work.
    private val executed = HashSet<RequestKey>()
    private val retiredSessions = HashSet<Pair<String, String>>()
    private val announcedSessions = HashMap<String, String>()
    private var inflight = 0
    private val inbound = mutableMapOf<Job, Pair<String, String>>()
    private val voteMutex = Mutex()
    private var activeRound: Pair<String, ConcurrentHashMap<String, Double>>? = null

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()
    private val _home = MutableStateFlow(JSONObject().put("joined", false).put("home_id", "").put("name", ""))
    val home: StateFlow<JSONObject> = _home.asStateFlow()
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()
    private val _maySleep = MutableStateFlow(false)
    val maySleep: StateFlow<Boolean> = _maySleep.asStateFlow()
    private val _lastVote = MutableStateFlow<VoteResult?>(null)
    val lastVote: StateFlow<VoteResult?> = _lastVote.asStateFlow()

    val running: Boolean get() = synchronized(this) { socket?.isClosed == false && lifecycle?.isActive == true }
    val nodeId: String get() = synchronized(this) { ensureLoaded(); config.id }
    val settings: MeshConfig get() = synchronized(this) { ensureLoaded(); config }

    @Synchronized
    fun membership(): JSONObject {
        ensureLoaded()
        return publicMembership()
    }

    private fun publicMembership(): JSONObject = JSONObject().put("joined", credentials != null)
        .put("home_id", credentials?.home ?: "").put("name", config.name)

    @Synchronized
    fun createHome(): Unit = replaceMembership(HomeLink.create())

    @Synchronized
    fun joinHome(invite: String): Unit {
        val parsed = try { HomeLink.parseInvite(invite) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid home invite")
        }
        replaceMembership(parsed)
    }

    @Synchronized
    fun leaveHome(): Unit = replaceMembership(null)

    @Synchronized
    fun exportInvite(): String {
        ensureLoaded()
        return HomeLink.invite(credentials ?: throw IllegalStateException("No home joined"))
    }

    private fun replaceMembership(next: HomeLink.Credentials?) {
        ensureLoaded()
        // Commit before changing live state. A failed write must never silently change membership.
        persist(config, next)
        val restart = requestedStart
        stopTransport()
        credentials?.key?.fill(0)
        credentials = next
        storageHealthy = true
        _error.value = ""
        _home.value = publicMembership()
        publishPeers()
        if (restart) start()
    }

    @Synchronized
    fun updateConfig(name: String? = null, role: String? = null, priority: Int? = null, port: Int? = null): MeshConfig {
        ensureLoaded()
        val next = config.copy(
            name = name?.trim()?.takeIf { HomeLink.validText(it, 128) } ?: config.name,
            role = role?.trim()?.takeIf { HomeLink.validText(it, 32) } ?: config.role,
            priority = (priority ?: config.priority).coerceIn(0, 100),
            port = (port ?: config.port).coerceIn(1024, 65_535),
        )
        // Corrupt storage can only be replaced by an explicit membership action, not config edits.
        check(storageHealthy) { "Home storage unavailable" }
        persist(next, credentials)
        val restart = running && next.port != config.port
        config = next
        if (restart) { stopTransport(); start() }
        _home.value = publicMembership()
        publishPeers()
        return config
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val raw: String? = try { file.openRead().use<java.io.FileInputStream, String> { input ->
                val bytes = ByteArray(HomeLink.MAX_PACKET + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count <= HomeLink.MAX_PACKET)
                HomeLink.utf8(bytes.copyOf(count))
            } } catch (missing: java.io.FileNotFoundException) {
                if (file.baseFile.exists()) throw missing
                null
            }
            if (raw != null) {
                val json = HomeLink.parse(raw)
                val id = json.opt("id") as? String ?: error("Invalid settings")
                val name = json.opt("name") as? String ?: error("Invalid settings")
                val role = json.opt("role") as? String ?: error("Invalid settings")
                val priority = HomeLink.integer(json, "priority") ?: error("Invalid settings")
                val port = HomeLink.integer(json, "port") ?: error("Invalid settings")
                require(HomeLink.validText(id, 128) && HomeLink.validText(name, 128) &&
                    HomeLink.validText(role, 32) && priority in 0..100 && port in 1024..65_535)
                // Legacy local settings may be migrated, but never create/join a household.
                val next = if (!json.has("membership") || json.isNull("membership")) null else {
                    val member = json.getJSONObject("membership")
                    HomeLink.parseInvite("amalia-home:v2:${member.getString("home")}:${member.getString("key")}")
                }
                config = MeshConfig(id, name, role, priority, port)
                credentials = next
            }
            persist(config, credentials) // Also make a newly generated node id durable before networking.
        } catch (_: Exception) {
            credentials = null
            storageHealthy = false
            _error.value = "Не удалось прочитать или сохранить настройки дома. Сеть отключена."
        }
        _home.value = publicMembership()
        publishPeers()
    }

    private fun persist(settings: MeshConfig, member: HomeLink.Credentials?) {
        val json = JSONObject().put("id", settings.id).put("name", settings.name).put("role", settings.role)
            .put("priority", settings.priority).put("port", settings.port)
        if (member == null) json.put("membership", JSONObject.NULL) else {
            val parts = HomeLink.invite(member).split(':')
            json.put("membership", JSONObject().put("home", member.home).put("key", parts[3]))
        }
        var stream: java.io.FileOutputStream? = null
        try {
            check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            val output = file.startWrite()
            stream = output
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (_: Exception) {
            stream?.let { file.failWrite(it) }
            _error.value = "Не удалось сохранить настройки дома."
            throw IllegalStateException("Could not save home settings")
        }
    }

    @Synchronized
    fun start() {
        requestedStart = true
        ensureLoaded()
        if (running) return
        stopTransport()
        if (!scope.isActive || !storageHealthy || credentials == null) return
        _error.value = ""
        val udp = try {
            DatagramSocket(null).also {
                try {
                    it.reuseAddress = true
                    it.broadcast = true
                    it.soTimeout = 1_000
                    it.bind(InetSocketAddress(config.port))
                } catch (error: Exception) { it.close(); throw error }
            }
        } catch (_: Exception) {
            _error.value = "Не удалось открыть порт сети дома."
            return
        }
        socket = udp
        val owner = SupervisorJob(scope.coroutineContext[Job])
        lifecycle = owner
        val childScope = CoroutineScope(scope.coroutineContext + owner + Dispatchers.IO)
        workers = childScope
        acquireMulticastLock()
        owner.invokeOnCompletion { synchronized(this) { if (socket === udp) stopTransport() } }
        val receive = childScope.launch(start = CoroutineStart.LAZY) { receiveLoop(udp) }
        val announce = childScope.launch(start = CoroutineStart.LAZY) { announceLoop(udp) }
        receive.invokeOnCompletion { stopIfCurrent(udp) }
        announce.invokeOnCompletion { stopIfCurrent(udp) }
        receive.start()
        announce.start()
        if (!owner.isActive) stopTransport()
        publishPeers()
    }

    @Synchronized
    fun stop() {
        requestedStart = false
        stopTransport()
    }

    private fun stopTransport() {
        val old = socket
        socket = null
        val job = lifecycle
        lifecycle = null
        workers = null
        session = HomeLink.uuid()
        old?.close()
        pending.values.forEach { it.result.complete(failure("Request interrupted", "unknown")) }
        pending.clear()
        job?.cancel()
        inflight = 0
        inbound.clear()
        activeRound = null
        replay.clear()
        executed.clear()
        retiredSessions.clear()
        announcedSessions.clear()
        peers.clear()
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
        _maySleep.value = false
        _lastVote.value = null
        publishPeers()
    }

    @Synchronized
    private fun stopIfCurrent(udp: DatagramSocket) { if (socket === udp) stopTransport() }

    private fun acquireMulticastLock() {
        _maySleep.value = true
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("amalia-unified-home").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
            _maySleep.value = false
        } catch (_: Exception) { _maySleep.value = true }
    }

    private suspend fun announceLoop(udp: DatagramSocket) {
        while (currentCoroutineContext().isActive) {
            synchronized(this) {
                if (socket !== udp) return
                send(udp, JSONObject().put("t", "announce").put("name", config.name).put("role", config.role)
                    .put("priority", config.priority).put("port", config.port).put("platform", "android")
                    .put("services", JSONArray(HomeLink.operations.toList())))
                publishPeers()
            }
            delay(ANNOUNCE_INTERVAL_MS)
        }
    }

    private suspend fun receiveLoop(udp: DatagramSocket) {
        val buffer = ByteArray(HomeLink.MAX_PACKET + 1)
        while (currentCoroutineContext().isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                if (packet.length == 0 || packet.length > HomeLink.MAX_PACKET) continue
                val work = synchronized(this) {
                    if (socket !== udp) return
                    val member = credentials ?: return
                    val message = HomeLink.decode(member, packet.data, packet.length)
                    if (message == null) null else handle(udp, message, packet.address.hostAddress ?: "")
                }
                // The callback job is dispatched only after releasing the mesh monitor.
                work?.start()
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                synchronized(this) { if (socket === udp) _error.value = "Сеть дома недоступна." }
                return
            } catch (_: Exception) {
                // Invalid/untrusted datagrams must not terminate the service.
            }
        }
    }

    /** Called under monitor; only lazy job construction is allowed here, not user code. */
    private fun handle(udp: DatagramSocket, message: HomeLink.Message, host: String): Job? {
        if (message.id == config.id || host.isBlank()) return null
        val now = System.currentTimeMillis()
        replay.entries.removeAll { now - it.value > REPLAY_TTL_MS }
        val replayKey = ReplayKey(message.id, message.session, message.nonce)
        if (replay.containsKey(replayKey) || replay.size >= MAX_HISTORY) return null
        replay[replayKey] = now
        val body = message.body
        if (body.opt("t") == "announce") {
            val name = body.opt("name") as? String ?: return null
            val role = body.opt("role") as? String ?: return null
            val priority = HomeLink.integer(body, "priority") ?: return null
            val port = HomeLink.integer(body, "port") ?: return null
            val platform = body.opt("platform") as? String ?: return null
            val servicesJson = body.opt("services") as? JSONArray ?: return null
            if (!HomeLink.validText(name, 128) || !HomeLink.validText(role, 32) || priority !in 0..100 ||
                port !in 1024..65_535 || platform !in setOf("android", "pc") || servicesJson.length() > 3) return null
            val services = (0 until servicesJson.length()).map {
                servicesJson.opt(it) as? String ?: return null
            }
            if (services.any { it !in HomeLink.operations } || services.toSet().size != services.size ||
                (message.id to message.session) in retiredSessions) return null
            prunePeers()
            if (!peers.containsKey(message.id) && peers.size >= MAX_PEERS) return null
            val previousSession = announcedSessions[message.id]
            if (previousSession == null && announcedSessions.size >= MAX_HISTORY) return null
            if (previousSession != null && previousSession != message.session) {
                if (retiredSessions.size >= MAX_HISTORY) return null
                retiredSessions.add(message.id to previousSession)
                failPending(message.id)
                inbound.filterValues { it == (message.id to previousSession) }.keys.toList().forEach { it.cancel() }
            }
            announcedSessions[message.id] = message.session
            peers[message.id] = MeshNode(message.id, name, role, priority, host, port, now,
                session = message.session, platform = platform, services = services)
            publishPeers()
            return null
        }
        val peer = peers[message.id]?.takeIf { it.online(now) && it.session == message.session } ?: return null
        when (body.opt("t")) {
            "vote" -> {
                val round = body.opt("round") as? String ?: return null
                val score = (body.opt("score") as? Number)?.toDouble() ?: return null
                if (!HomeLink.validUuid(round) || !score.isFinite() || score !in 0.0..100.0) return null
                activeRound?.takeIf { it.first == round }?.second?.putIfAbsent(peer.id, score)
            }
            "request", "result" -> {
                if (body.opt("to") != config.id || body.opt("to_session") != session) return null
                val id = body.opt("request_id") as? String ?: return null
                val operation = body.opt("op") as? String ?: return null
                if (!HomeLink.validUuid(id) || operation !in HomeLink.operations) return null
                if (body.opt("t") == "result") {
                    val wait = pending[id] ?: return null
                    if (wait.peer != peer.id || wait.peerSession != peer.session || wait.operation != operation) return null
                    val ok = body.opt("ok") as? Boolean ?: return null
                    val data = body.opt("data") as? JSONObject ?: return null
                    if (body.opt("error") !is String) return null
                    pending.remove(id)
                    wait.result.complete(JSONObject().put("ok", ok).put("data", data)
                        .put("error", if (ok) "" else "Remote request failed").also {
                            if (!ok && operation == "apply_plan") it.put("status", "unknown")
                        })
                } else {
                    val args = body.opt("args") as? JSONObject ?: return null
                    val key = RequestKey(peer.id, peer.session, id)
                    if (key in executed) return null
                    if (executed.size >= MAX_HISTORY) return null
                    executed.add(key) // Includes overload rejections; retries must not turn into execution.
                    if (inflight >= MAX_WORK) {
                        sendResult(udp, peer, id, operation, failure("Remote node busy"))
                        return null
                    }
                    val childScope = workers ?: return null
                    val ourSession = session
                    inflight++
                    val requestJob = childScope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val valid = synchronized(this@MeshService) {
                                socket === udp && session == ourSession && peers[peer.id]?.let {
                                    it.session == peer.session && it.online()
                                } == true
                            }
                            if (!valid) return@launch
                            val result = try {
                                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                                    normalize(onRequest(operation, args, peer.id, peer.session))
                                } ?: failure("Remote request timed out", "unknown")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failure("Remote request failed") }
                            synchronized(this@MeshService) {
                                if (socket === udp && session == ourSession && peers[peer.id]?.session == peer.session) {
                                    if (!sendResult(udp, peer, id, operation, result)) {
                                        sendResult(udp, peer, id, operation, failure("Remote result unavailable"))
                                    }
                                }
                            }
                        } finally {
                            synchronized(this@MeshService) { if (session == ourSession) inflight-- }
                        }
                    }
                    inbound[requestJob] = peer.id to peer.session
                    requestJob.invokeOnCompletion { synchronized(this@MeshService) { inbound.remove(requestJob) } }
                    return requestJob
                }
            }
        }
        return null
    }

    private fun normalize(value: JSONObject): JSONObject {
        val ok = value.opt("ok") as? Boolean ?: return failure("Remote request failed")
        val data = value.opt("data") as? JSONObject ?: return failure("Remote request failed")
        // Callback errors must never leak stack traces, credentials or addresses onto the LAN.
        return JSONObject().put("ok", ok).put("data", data).put("error", if (ok) "" else "Remote request failed")
    }

    private fun sendResult(udp: DatagramSocket, peer: MeshNode, id: String, operation: String, result: JSONObject): Boolean =
        send(udp, JSONObject().put("t", "result").put("to", peer.id).put("to_session", peer.session)
            .put("request_id", id).put("op", operation).put("ok", result.getBoolean("ok"))
            .put("data", result.getJSONObject("data")).put("error", result.getString("error")), peer)

    /** Single-shot authenticated RPC. A lost/late result is unknown, never presumed successful. */
    suspend fun request(nodeId: String, operation: String, args: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        if (operation !in HomeLink.operations) return@withContext failure("Unsupported operation")
        val id = HomeLink.uuid()
        val deferred = CompletableDeferred<JSONObject>()
        val wait = synchronized(this@MeshService) {
            ensureLoaded()
            val udp = socket
            val peer = peers[nodeId]
            if (udp == null || !running || credentials == null || peer == null || !peer.online()) {
                return@withContext failure("Node unavailable")
            }
            if (operation !in peer.services) return@withContext failure("Operation unavailable")
            if (pending.size >= MAX_WORK) return@withContext failure("Too many pending requests")
            val value = Pending(peer.id, peer.session, operation, deferred)
            pending[id] = value
            val sent = send(udp, JSONObject().put("t", "request").put("to", peer.id)
                .put("to_session", peer.session).put("request_id", id).put("op", operation).put("args", args), peer)
            if (!sent) {
                pending.remove(id)
                return@withContext failure("Request could not be sent", "unknown")
            }
            value
        }
        try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() } ?: failure("Request timed out", "unknown")
        } finally {
            synchronized(this@MeshService) { if (pending[id] === wait) pending.remove(id) }
        }
    }

    private fun failure(error: String, status: String? = null): JSONObject =
        JSONObject().put("ok", false).put("data", JSONObject()).put("error", error).also {
            if (status != null) it.put("status", status)
        }

    private fun failPending(peerId: String) {
        val ids = pending.filterValues { it.peer == peerId }.keys.toList()
        ids.forEach { pending.remove(it)?.result?.complete(failure("Peer session changed", "unknown")) }
    }

    private fun prunePeers() {
        val stale = peers.values.filter { !it.online() }.map { it.id }
        stale.forEach { peers.remove(it); failPending(it) }
    }

    private fun publishPeers() {
        prunePeers()
        val self = MeshNode(config.id, config.name, config.role, config.priority, "—", config.port,
            System.currentTimeMillis(), self = true, session = session, services = HomeLink.operations.toList())
        _nodes.value = listOf(self) + peers.values.sortedByDescending { it.priority }
    }

    /** All sends run on IO workers, serialized with membership changes. */
    private fun send(udp: DatagramSocket, body: JSONObject, peer: MeshNode? = null): Boolean {
        if (socket !== udp || udp.isClosed || lifecycle?.isActive != true) return false
        val member = credentials ?: return false
        return runCatching {
            val payload = HomeLink.encode(member, config.id, session, body) ?: return false
            val address = InetAddress.getByName(peer?.host ?: "255.255.255.255")
            udp.send(DatagramPacket(payload, payload.size, address, peer?.port ?: config.port))
            true
        }.getOrDefault(false)
    }

    /** Diagnostic only; deliberately not connected to microphone/VAD or automatic execution. */
    suspend fun vote(score: Double, windowMs: Long = VOTE_WINDOW_MS): VoteResult = withContext(Dispatchers.IO) {
        require(score.isFinite() && score in 0.0..100.0) { "Score must be finite and in 0..100" }
        require(windowMs in 0L..5_000L) { "Vote window must be in 0..5000 ms" }
        voteMutex.withLock {
            val votes = ConcurrentHashMap<String, Double>()
            val round = HomeLink.uuid() to votes
            val snapshot = settings
            votes[snapshot.id] = score
            var sent = false
            val startedSession: String
            val udp: DatagramSocket?
            synchronized(this@MeshService) {
                startedSession = session
                udp = socket
                if (udp != null && running) {
                    activeRound = round
                    sent = send(udp, JSONObject().put("t", "vote").put("round", round.first).put("score", score))
                }
            }
            try { if (sent) delay(windowMs) }
            finally { synchronized(this@MeshService) { if (activeRound === round) activeRound = null } }
            synchronized(this@MeshService) {
                val localOnly = !sent || socket !== udp || startedSession != session || !running
                if (localOnly) { votes.clear(); votes[snapshot.id] = score }
                val candidates = votes.entries.filter { it.key == snapshot.id || peers[it.key]?.online() == true }
                val winner = candidates.maxWithOrNull(compareBy<Map.Entry<String, Double>> { it.value }
                    .thenBy { peers[it.key]?.priority ?: snapshot.priority }.thenBy { it.key })!!
                val result = VoteResult(winner.key, if (winner.key == snapshot.id) snapshot.name else peers[winner.key]?.name ?: winner.key,
                    winner.value, candidates.size, winner.key == snapshot.id, localOnly)
                if (startedSession == session) _lastVote.value = result
                result
            }
        }
    }

    @Deprecated("Use explicit plan_scene/apply_plan RPC with human agreement")
    @Suppress("UNUSED_PARAMETER")
    suspend fun handoff(targetNodeId: String, sceneId: String): Boolean = false
}
