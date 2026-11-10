package org.amalia.unifiedhome

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.NetworkInterface

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
    val coordinationVersion: Int = 0,
    val secureVersion: Int = 0,
    val tcpPort: Int? = null,
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
    val coordination: CoordinationPreferences = CoordinationPreferences(),
    val seeds: List<String> = emptyList(),
)

class MeshService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRequest: suspend (operation: String, args: JSONObject, fromNode: String, fromSession: String) -> JSONObject,
) {
    companion object {
        const val DEFAULT_PORT = 47654
        const val NODE_TTL_MS = 45_000L
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
        val result: CompletableDeferred<JSONObject>, val caller: Job?, val expiresAt: Long,
    )
    private data class SecurePending(
        val peer: String, val peerSession: String, val timeoutMs: Long,
        val result: CompletableDeferred<JSONObject>, val caller: Job?, val expiresAt: Long,
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
    private var reliable: ReliableTransport? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val discovery = Channel<Unit>(Channel.CONFLATED)
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
    val coordination = HomeCoordination(this)

    /** Opt-in application dispatcher only: the transport never executes commands or code itself.
     * Runs on bounded IO workers, outside the mesh monitor; must cooperate with cancellation.
     * Return {ok: Boolean, data: JSONObject, error: String}. Exceptions/errors are sanitized.
     */
    @Volatile
    var secureHandler: (suspend (operation: String, args: JSONObject, from: String, session: String) -> JSONObject)? = null
    private val securePending = mutableMapOf<String, SecurePending>()
    private val secureExecuted = HashSet<RequestKey>()
    private val secureInbound = mutableMapOf<Job, Pair<String, String>>()

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

    val running: Boolean get() = synchronized(this) { socket?.isClosed == false && reliable?.running == true && lifecycle?.isActive == true }
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
    fun updateConfig(name: String? = null, role: String? = null, priority: Int? = null, port: Int? = null, seeds: List<String>? = null): MeshConfig {
        ensureLoaded()
        val next = config.copy(
            name = name?.trim()?.takeIf { HomeLink.validText(it, 128) } ?: config.name,
            role = role?.trim()?.takeIf { HomeLink.validText(it, 32) } ?: config.role,
            priority = (priority ?: config.priority).coerceIn(0, 100),
            port = (port ?: config.port).coerceIn(1024, 65_535),
            seeds = seeds?.let { validateSeeds(it) } ?: config.seeds,
        )
        // Corrupt storage can only be replaced by an explicit membership action, not config edits.
        check(storageHealthy) { "Home storage unavailable" }
        persist(next, credentials)
        val restart = requestedStart && next.port != config.port
        config = next
        if (restart) { stopTransport(); start() }
        discovery.trySend(Unit)
        _home.value = publicMembership()
        publishPeers()
        return config
    }

    @Synchronized
    fun updateCoordination(preferences: CoordinationPreferences): CoordinationPreferences {
        ensureLoaded()
        check(storageHealthy) { "Home storage unavailable" }
        val validated = CoordinationPreferences.fromJson(preferences.toJson())
        val next = config.copy(coordination = validated)
        persist(next, credentials)
        config = next
        coordination.clear() // Routing changes invalidate pending decisions and coordinated plans.
        publishPeers()
        return preferences
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
                config = MeshConfig(id, name, role, priority, port,
                    json.optJSONObject("coordination")?.let { CoordinationPreferences.fromJson(it) } ?: CoordinationPreferences(),
                    if (!json.has("seeds")) emptyList() else {
                        val seeds = json.getJSONArray("seeds")
                        require(seeds.length() <= 32)
                        validateSeeds((0 until seeds.length()).map { seeds.get(it) as? String ?: error("Invalid seed") })
                    })
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
            .put("coordination", settings.coordination.toJson()).put("seeds", JSONArray(settings.seeds))
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

    /** Hosts only: no scheme/path/port, no DNS lookup while validating persisted settings. */
    private fun validateSeeds(values: List<String>): List<String> {
        require(values.size <= 32) { "At most 32 seed hosts" }
        return values.map { value ->
            val host = value.trim()
            require(host.length in 1..253 && host.split('.').all { label ->
                label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                    label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            }) { "Seed must be an IPv4 address or hostname without URL or port" }
            if (host.all { it in '0'..'9' || it == '.' }) {
                require(host.split('.').let { parts ->
                    parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
                }) {
                    "Invalid IPv4 seed"
                }
            }
            host.lowercase(java.util.Locale.ROOT)
        }.distinct()
    }

    @Synchronized
    fun start() {
        requestedStart = true
        ensureLoaded()
        if (lifecycle?.isActive == true) { discovery.trySend(Unit); return }
        stopTransport()
        if (!scope.isActive || !storageHealthy || credentials == null) return
        _error.value = ""
        val owner = SupervisorJob(scope.coroutineContext[Job])
        lifecycle = owner
        val childScope = CoroutineScope(scope.coroutineContext + owner + Dispatchers.IO)
        workers = childScope
        acquireMulticastLock()
        registerNetworkCallback()
        owner.invokeOnCompletion { synchronized(this) { if (lifecycle === owner) stopTransport() } }
        // Bind/receive failures never erase requestedStart. Retry even without Android callbacks.
        childScope.launch {
            while (currentCoroutineContext().isActive) {
                synchronized(this@MeshService) {
                    if (lifecycle !== owner) return@launch
                    publishPeers()
                    if (socket?.isClosed != false || reliable?.running != true) {
                        socket?.close()
                        socket = null
                        reliable?.close()
                        reliable = null
                        var udp: DatagramSocket? = null
                        try {
                            val bound = DatagramSocket(null)
                            udp = bound
                            bound.reuseAddress = true
                            bound.broadcast = true
                            bound.soTimeout = 1_000
                            bound.bind(InetSocketAddress(config.port))
                            val tcp = ReliableTransport(config.port) { payload, host ->
                                dispatch(bound, payload, payload.size, host)
                            }
                            socket = bound
                            reliable = tcp
                            _error.value = ""
                            childScope.launch { receiveLoop(bound) }
                            discovery.trySend(Unit)
                        } catch (_: Exception) {
                            udp?.close()
                            _error.value = "Не удалось открыть порт сети дома. Повторяем подключение."
                        }
                    }
                }
                delay(1_000)
            }
        }
        childScope.launch { announceLoop(owner) }
        publishPeers()
    }

    private fun registerNetworkCallback() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { discovery.trySend(Unit) }
            override fun onLost(network: Network) { discovery.trySend(Unit) }
            override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
                discovery.trySend(Unit)
            }
        }
        try {
            // LAN-only Wi-Fi/Ethernet can be useful without INTERNET or VALIDATED capabilities.
            manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), callback)
            networkCallback = callback
        } catch (_: Exception) {
            // Missing host permission/callback quota does not prevent periodic socket recovery.
            networkCallback = null
        }
    }

    @Synchronized
    fun stop() {
        requestedStart = false
        stopTransport()
    }

    private fun stopTransport() {
        val old = socket
        socket = null
        reliable?.close()
        reliable = null
        networkCallback?.let { callback ->
            runCatching { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        val job = lifecycle
        lifecycle = null
        workers = null
        session = HomeLink.uuid()
        old?.close()
        pending.values.forEach { it.result.complete(failure("Request interrupted", "unknown")) }
        pending.clear()
        securePending.values.forEach { it.result.complete(SecureHome.failure("Secure request interrupted; outcome unknown")) }
        securePending.clear()
        secureInbound.keys.toList().forEach { it.cancel() }
        secureInbound.clear()
        secureExecuted.clear()
        job?.cancel()
        inflight = 0
        inbound.clear()
        coordination.clear()
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

    private fun announcement(): JSONObject = JSONObject().put("t", "announce").put("name", config.name)
        .put("role", config.role).put("priority", config.priority).put("port", config.port)
        .put("tcp_port", config.port).put("platform", "android")
        .put("services", JSONArray(HomeLink.operations.toList()))
        .put("coordination_version", HomeCoordination.VERSION).put("secure_version", SecureHome.VERSION)

    private suspend fun announceLoop(owner: Job) {
        while (currentCoroutineContext().isActive) {
            withTimeoutOrNull(ANNOUNCE_INTERVAL_MS) { discovery.receive() }
            val snapshot = synchronized(this) {
                if (lifecycle !== owner) return
                publishPeers()
                val udp = socket ?: return@synchronized null
                val member = credentials ?: return@synchronized null
                val payload = HomeLink.encode(member, config.id, session, announcement()) ?: return@synchronized null
                Triple(udp, payload, config to peers.values.toList())
            } ?: continue
            val (udp, payload, destinations) = snapshot
            val (settings, knownPeers) = destinations
            val targets = linkedSetOf("255.255.255.255")
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    iface.interfaceAddresses.forEach { address ->
                        (address.broadcast as? Inet4Address)?.hostAddress?.let { targets.add(it) }
                    }
                }
            } catch (_: Exception) {
                // Global broadcast and explicit seeds remain available if interface enumeration fails.
            }
            targets.addAll(settings.seeds)
            val endpoints = targets.map { it to settings.port }.toMutableSet()
            // Broadcast may be filtered after seed bootstrap; sustain mutual presence by unicast.
            knownPeers.forEach { endpoints.add(it.host to it.port) }
            for ((target, port) in endpoints) {
                if (!currentCoroutineContext().isActive ||
                    !synchronized(this) { lifecycle === owner && socket === udp && !udp.isClosed }) break
                try {
                    // Resolve all IPv4 addresses: localhost/dual-stack names must not hide LAN IPv4.
                    InetAddress.getAllByName(target).filterIsInstance<Inet4Address>().forEach { address ->
                        if (!currentCoroutineContext().isActive ||
                            !synchronized(this) { lifecycle === owner && socket === udp && !udp.isClosed }) return@forEach
                        udp.send(DatagramPacket(payload, payload.size, address, port))
                    }
                } catch (_: Exception) {
                    // A failed route/seed must not suppress other interfaces or stop discovery.
                }
            }
        }
    }

    private fun dispatch(udp: DatagramSocket, payload: ByteArray, length: Int, host: String) {
        val work = synchronized(this) {
            if (socket !== udp || udp.isClosed || lifecycle?.isActive != true || length !in 1..HomeLink.MAX_PACKET) return
            val member = credentials ?: return
            val message = HomeLink.decode(member, payload, length) ?: return
            handle(udp, message, host)
        }
        work?.start() // No application callback under the mesh monitor, on either transport.
    }

    private suspend fun receiveLoop(udp: DatagramSocket) {
        val buffer = ByteArray(HomeLink.MAX_PACKET + 1)
        var receiveErrors = 0
        while (currentCoroutineContext().isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                receiveErrors = 0
                if (packet.length == 0 || packet.length > HomeLink.MAX_PACKET) continue
                dispatch(udp, packet.data, packet.length, packet.address.hostAddress.orEmpty())
                synchronized(this) { if (socket === udp) _error.value = "" }
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                synchronized(this) { if (socket === udp) _error.value = "Сеть дома недоступна." }
                if (udp.isClosed) return
                // Isolated ICMP does not kill transport. Rebind persistent failures without clearing
                // our session, replay history or executed IDs (otherwise side effects could repeat).
                if (++receiveErrors >= 4) { udp.close(); return }
                delay(250)
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
            val tcpPort = if (body.has("tcp_port")) {
                HomeLink.integer(body, "tcp_port")?.takeIf { it in 1024..65_535 } ?: return null
            } else null
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
            val newlySeen = !peers.containsKey(message.id)
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
                session = message.session, platform = platform, services = services,
                coordinationVersion = HomeLink.integer(body, "coordination_version")?.takeIf { it == HomeCoordination.VERSION } ?: 0,
                secureVersion = HomeLink.integer(body, "secure_version")?.takeIf { it == SecureHome.VERSION } ?: 0,
                tcpPort = tcpPort)
            if (newlySeen || previousSession != message.session) {
                // Bootstrap mutual presence once, not a reply storm for every heartbeat.
                send(udp, announcement(), peers[message.id], discoveryOnly = true)
            }
            publishPeers()
            return null
        }
        val peer = peers[message.id]?.takeIf { it.online(now) && it.session == message.session } ?: return null
        if (body.opt("t") == "secure_request" || body.opt("t") == "secure_result") {
            return handleSecure(udp, message, peer)
        }
        when (body.opt("t")) {
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
                    val expiresAt = android.os.SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS
                    inflight++
                    val requestJob = childScope.launch(start = CoroutineStart.LAZY) {
                        val valid = synchronized(this@MeshService) {
                            lifecycle?.isActive == true && session == ourSession && peers[peer.id]?.let {
                                it.session == peer.session && it.online()
                            } == true
                        }
                        if (!valid) return@launch
                        val result = try {
                            withTimeoutOrNull((expiresAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)) {
                                normalize(if (operation == "catalog" && args.has("coordination")) {
                                    coordination.handle(args)
                                } else {
                                    onRequest(operation, args, peer.id, peer.session)
                                })
                            } ?: failure("Remote request timed out", "unknown")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failure("Remote request failed") }
                        synchronized(this@MeshService) {
                            // A socket rebind is not a membership/session change. Reply on the live route.
                            val currentSocket = socket
                            val currentPeer = peers[peer.id]
                            if (currentSocket != null && session == ourSession &&
                                currentPeer?.session == peer.session && currentPeer.online()) {
                                if (!sendResult(currentSocket, currentPeer, id, operation, result)) {
                                    sendResult(currentSocket, currentPeer, id, operation, failure("Remote result unavailable"))
                                }
                            }
                        }
                    }
                    inbound[requestJob] = peer.id to peer.session
                    // A lazy job can be cancelled before its body (and its finally) ever runs.
                    requestJob.invokeOnCompletion {
                        synchronized(this@MeshService) {
                            if (inbound.remove(requestJob) != null && session == ourSession) inflight--
                        }
                    }
                    return requestJob
                }
            }
        }
        return null
    }

    /** Under mesh monitor; never invokes the optional application handler here. */
    private fun handleSecure(udp: DatagramSocket, message: HomeLink.Message, peer: MeshNode): Job? {
        if (peer.secureVersion != SecureHome.VERSION) return null
        val member = credentials ?: return null
        val opened = SecureHome.open(member, message, config.id, session) ?: return null
        val payload = opened.payload
        if (message.body.opt("t") == "secure_result") {
            val wait = securePending[opened.id] ?: return null
            if (wait.peer != peer.id || wait.peerSession != peer.session || wait.timeoutMs != opened.timeoutMs) return null
            if (payload.keys().asSequence().toSet() != setOf("ok", "data", "error") ||
                payload.opt("ok") !is Boolean || payload.opt("data") !is JSONObject || payload.opt("error") !is String) return null
            securePending.remove(opened.id)
            wait.result.complete(payload)
            return null
        }
        if (payload.keys().asSequence().toSet() != setOf("op", "args")) return null
        val operation = payload.opt("op") as? String ?: return null
        val args = payload.opt("args") as? JSONObject ?: return null
        if (!HomeLink.validText(operation, 128)) return null
        val key = RequestKey(peer.id, peer.session, opened.id)
        if (key in secureExecuted) return null
        if (secureExecuted.size >= MAX_HISTORY) {
            sendSecureResult(udp, peer, opened.id, opened.timeoutMs, SecureHome.failure("Secure request history full; restart session"))
            return null // Never evict old IDs to admit execution.
        }
        secureExecuted.add(key) // Also remember overload/unavailable decisions; never execute a retry.
        if (secureInbound.size >= MAX_WORK) {
            sendSecureResult(udp, peer, opened.id, opened.timeoutMs, SecureHome.failure("Secure node busy"))
            return null
        }
        val handler = secureHandler
        if (handler == null) {
            sendSecureResult(udp, peer, opened.id, opened.timeoutMs, SecureHome.failure("Secure handler unavailable"))
            return null
        }
        val childScope = workers ?: return null
        val ourSession = session
        val expiresAt = android.os.SystemClock.elapsedRealtime() + opened.timeoutMs
        val requestJob = childScope.launch(start = CoroutineStart.LAZY) {
            val valid = synchronized(this@MeshService) {
                lifecycle?.isActive == true && session == ourSession && peers[peer.id]?.let {
                    it.session == peer.session && it.online()
                } == true
            }
            if (!valid) return@launch
            val result = try {
                withTimeoutOrNull((expiresAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)) {
                    val value = handler(operation, args, peer.id, peer.session)
                    try { SecureHome.snapshot(normalize(value)) }
                    catch (_: Exception) { SecureHome.failure("Secure result exceeds payload or JSON limits") }
                } ?: SecureHome.failure("Secure handler timed out; outcome unknown")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SecureHome.failure("Secure handler failed") }
            synchronized(this@MeshService) {
                val currentSocket = socket
                val currentPeer = peers[peer.id]
                if (currentSocket != null && session == ourSession &&
                    currentPeer?.session == peer.session && currentPeer.online()) {
                    if (!sendSecureResult(currentSocket, currentPeer, opened.id, opened.timeoutMs, result)) {
                        sendSecureResult(currentSocket, currentPeer, opened.id, opened.timeoutMs, SecureHome.failure("Secure result could not be sent"))
                    }
                }
            }
        }
        secureInbound[requestJob] = peer.id to peer.session
        requestJob.invokeOnCompletion { synchronized(this@MeshService) { secureInbound.remove(requestJob) } }
        return requestJob
    }

    private fun sendSecureResult(udp: DatagramSocket, peer: MeshNode, id: String, timeoutMs: Long, result: JSONObject): Boolean {
        val member = credentials ?: return false
        return try {
            send(udp, SecureHome.seal(member, config.id, session, peer.id, peer.session,
                id, "secure_result", timeoutMs, result), peer)
        } catch (_: Exception) { false }
    }

    /** Single authenticated frame (legacy UDP if unadvertised), no retries. Timeout/cancellation after send means outcome is unknown.
     * Complete plaintext JSON is capped at 8192 UTF-8 bytes; no truncation/fragmentation.
     * Requires a fresh announcement with secure_version=1; never falls back to cleartext RPC.
     */
    suspend fun secureRequest(nodeId: String, operation: String, args: JSONObject = JSONObject(),
        timeoutMs: Long = 12_000L): JSONObject = withContext(Dispatchers.IO) {
        if (timeoutMs !in 1..SecureHome.MAX_TIMEOUT_MS) return@withContext SecureHome.failure("Invalid secure timeout (1..120000 ms)")
        if (!HomeLink.validText(operation, 128)) return@withContext SecureHome.failure("Invalid secure operation")
        val payload = try { SecureHome.snapshot(JSONObject().put("op", operation).put("args", args)) }
            catch (_: Exception) { return@withContext SecureHome.failure("Secure request exceeds payload or JSON limits (8192 bytes)") }
        val id = HomeLink.uuid()
        val caller = currentCoroutineContext()[Job]
        val expiresAt = android.os.SystemClock.elapsedRealtime() + timeoutMs
        val deferred = CompletableDeferred<JSONObject>()
        val wait = synchronized(this@MeshService) {
            ensureLoaded()
            val udp = socket
            val member = credentials
            val peer = peers[nodeId]
            if (udp == null || !running || member == null || peer == null || !peer.online()) {
                return@withContext SecureHome.failure("Secure node unavailable")
            }
            if (peer.secureVersion != SecureHome.VERSION) return@withContext SecureHome.failure("Secure RPC unavailable")
            if (securePending.size >= MAX_WORK) return@withContext SecureHome.failure("Too many pending secure requests")
            val body = try { SecureHome.seal(member, config.id, session, peer.id, peer.session,
                id, "secure_request", timeoutMs, payload) }
                catch (_: Exception) { return@withContext SecureHome.failure("Secure request encryption failed") }
            val value = SecurePending(peer.id, peer.session, timeoutMs, deferred, caller, expiresAt)
            securePending[id] = value
            if (!send(udp, body, peer)) {
                securePending.remove(id)
                return@withContext SecureHome.failure("Secure request could not be sent; outcome unknown")
            }
            value
        }
        try {
            withTimeoutOrNull((expiresAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)) { deferred.await() }
                ?: SecureHome.failure("Secure request timed out; outcome unknown")
        } finally {
            synchronized(this@MeshService) { if (securePending[id] === wait) securePending.remove(id) }
        }
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
        val caller = currentCoroutineContext()[Job]
        val expiresAt = android.os.SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS
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
            val value = Pending(peer.id, peer.session, operation, deferred, caller, expiresAt)
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
            withTimeoutOrNull((expiresAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)) { deferred.await() } ?: failure("Request timed out", "unknown")
        } finally {
            synchronized(this@MeshService) { if (pending[id] === wait) pending.remove(id) }
        }
    }

    private fun failure(error: String, status: String? = null): JSONObject =
        JSONObject().put("ok", false).put("data", JSONObject()).put("error", error).also {
            if (status != null) it.put("status", status)
        }

    private fun failPending(peerId: String) {
        inbound.filterValues { it.first == peerId }.keys.toList().forEach { it.cancel() }
        val secureIds = securePending.filterValues { it.peer == peerId }.keys.toList()
        secureIds.forEach { securePending.remove(it)?.result?.complete(
            SecureHome.failure("Peer session changed or unavailable; outcome unknown")) }
        secureInbound.filterValues { it.first == peerId }.keys.toList().forEach { it.cancel() }
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
            System.currentTimeMillis(), self = true, session = session, services = HomeLink.operations.toList(),
            coordinationVersion = HomeCoordination.VERSION, secureVersion = SecureHome.VERSION, tcpPort = config.port)
        _nodes.value = listOf(self) + peers.values.sortedByDescending { it.priority }
    }

    /** Under mesh monitor; deferred activity alone does not reflect caller cancellation/expiry. */
    private fun requestCurrent(kind: String, id: String): Boolean = when (kind) {
        "request" -> pending[id]?.let {
            it.result.isActive && it.caller?.isActive != false &&
                android.os.SystemClock.elapsedRealtime() < it.expiresAt
        } == true
        "secure_request" -> securePending[id]?.let {
            it.result.isActive && it.caller?.isActive != false &&
                android.os.SystemClock.elapsedRealtime() < it.expiresAt
        } == true
        else -> true
    }

    /** Under mesh monitor. TCP is bounded/queued; never retry an uncertain send over UDP. */
    private fun send(udp: DatagramSocket, body: JSONObject, peer: MeshNode? = null,
        discoveryOnly: Boolean = false): Boolean {
        if (socket !== udp || udp.isClosed || lifecycle?.isActive != true) return false
        val member = credentials ?: return false
        val kind = body.optString("t")
        val requestId = body.optString("request_id")
        if (!requestCurrent(kind, requestId)) return false
        return try {
            val payload = HomeLink.encode(member, config.id, session, body) ?: return false
            if (peer != null && peer.tcpPort != null && !discoveryOnly) {
                val generation = session
                reliable?.send(peer.host, peer.tcpPort, payload) {
                    synchronized(this@MeshService) {
                        socket === udp && session == generation &&
                            peers[peer.id]?.let {
                                it.session == peer.session && it.host == peer.host &&
                                    it.tcpPort == peer.tcpPort && it.online()
                            } == true &&
                            requestCurrent(kind, requestId)
                    }
                } == true
            } else {
                val address = InetAddress.getByName(peer?.host ?: "255.255.255.255")
                if (!requestCurrent(kind, requestId)) return false
                udp.send(DatagramPacket(payload, payload.size, address, peer?.port ?: config.port))
                true
            }
        } catch (_: Exception) { false }
    }

    /** Diagnostic integration probe. One coordinator obtains fresh peer samples for a shared round. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun vote(score: Double, windowMs: Long = VOTE_WINDOW_MS): VoteResult = withContext(Dispatchers.IO) {
        require(windowMs in 0L..5_000L)
        coordination.publishProximity(score)
        val response = coordination.arbitrate(HomeLink.uuid())
        val data = response.optJSONObject("data")
        val winner = (data?.optJSONObject("routing")?.opt("responder") as? String).orEmpty()
        val node = nodes.value.firstOrNull { it.id == winner }
        val result = VoteResult(winner, node?.name.orEmpty(), if (winner == nodeId) score else 0.0,
            data?.optInt("participants", 0) ?: 0, winner == nodeId, !running)
        _lastVote.value = result
        result
    }

    @Deprecated("Use explicit plan_scene/apply_plan RPC with human agreement")
    @Suppress("UNUSED_PARAMETER")
    suspend fun handoff(targetNodeId: String, sceneId: String): Boolean = false
}
