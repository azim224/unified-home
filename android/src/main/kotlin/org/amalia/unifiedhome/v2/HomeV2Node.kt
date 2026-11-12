package org.amalia.unifiedhome.v2

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.SystemClock
import com.my.amali.plugin.HostServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * Host-managed network component: not an Android Service, no implicit joining or v1 migration.
 * Construct once per plugin lifetime. Call start/stop from the host background contributor.
 * Membership methods and exportInvitation are LOCAL HUMAN API ONLY: never register as LLM tools
 * or RPC handlers. Full-trust join is the grant; no extra per-task app grant layer is introduced.
 * Android and provider permissions/consent are still enforced by HostServices.
 */
class HomeV2Node(context: Context, private val host: HostServices, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val store = V2Store(context.applicationContext)
    private val gate = Mutex()
    private val lock = Any()
    private val replay = V2Replay()
    private val permits = Semaphore(16)
    private var runtime: Runtime? = null
    private val mutableNetworkStatus = MutableStateFlow(HomeV2NetworkStatus())
    /** Local transport diagnostics, not evidence of peer trust or LAN reachability. */
    val networkStatus: StateFlow<HomeV2NetworkStatus> = mutableNetworkStatus.asStateFlow()
    private val mutableStatus = MutableStateFlow(HomeV2Status(store.nodeId, store.membership?.home))
    val status: StateFlow<HomeV2Status> = mutableStatus.asStateFlow()
    private val mutablePeers = MutableStateFlow<List<HomeV2Peer>>(emptyList())
    val peers: StateFlow<List<HomeV2Peer>> = mutablePeers.asStateFlow()
    private var extension: (suspend (String, JSONObject, String) -> JSONObject?)? = null
    private var extensionOperations: Set<String> = emptySet()
    private val label = Build.MODEL.orEmpty().filter { !it.isISOControl() }.take(80).ifBlank { "Android" }
    private val platform = if (context.packageManager.hasSystemFeature("android.software.leanback")) "android_tv" else "android"

    private class Runtime(parent: CoroutineScope, val membership: V2Membership?, val port: Int) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO)
        val sockets = Collections.synchronizedSet(mutableSetOf<Closeable>())
        val refresh = Channel<Unit>(Channel.CONFLATED)
        val retry = Channel<Unit>(Channel.CONFLATED)
        @Volatile var server: ServerSocket? = null
        @Volatile var udp: DatagramSocket? = null
        @Volatile var udpAvailable = false
        var nsd: HomeV2Nsd? = null
        var connectivity: ConnectivityManager? = null
        var callback: ConnectivityManager.NetworkCallback? = null
        @Synchronized fun close() {
            callback?.let { try { connectivity?.unregisterNetworkCallback(it) } catch (_: Exception) { /* cleanup */ } }
            callback = null
            nsd?.stop()
            synchronized(sockets) { sockets.forEach { closeQuietly(it) }; sockets.clear() }
            closeQuietly(udp); closeQuietly(server)
        }
    }

    /** Installs optional data/context/profile handlers while STOPPED; null means unsupported.
     * The callback returns operation DATA (not an {ok,data,error} envelope). It receives only
     * the authenticated from UUID; the runtime checks current membership before invoking it.
     * Advertise only operations genuinely provided. Throwing fails with handler_failed.
     */
    suspend fun setOperationHandler(
        operations: Set<String>, handler: (suspend (operation: String, args: JSONObject, from: String) -> JSONObject?)?,
    ) = gate.withLock {
        v2Require(synchronized(lock) { runtime == null }, "stop_required")
        v2Require(operations.all { it.matches(Regex("[a-z][a-z0-9_.]{0,63}")) && it !in BUILT_INS })
        v2Require(handler != null || operations.isEmpty())
        extensionOperations = operations.toSet(); extension = handler
    }

    /** Creates trust but never exports its secret as a return value or flow emission. */
    suspend fun createHome(): HomeV2Status = changeMembership {
        v2Require(store.membership == null, "leave_required")
        V2Membership(V2Wire.randomHex(16), V2Wire.randomBytes(32))
    }
    /** Only amalia-home:v3 invitations accepted. Explicit leave is required before replacing trust. */
    suspend fun joinHome(invitation: String): HomeV2Status = changeMembership {
        v2Require(store.membership == null, "leave_required"); V2Store.parseInvitation(invitation)
    }
    suspend fun leaveHome(): HomeV2Status = changeMembership { null }
    suspend fun exportInvitation(): String = withContext(Dispatchers.IO) { gate.withLock {
        val m = store.membership ?: throw V2Failure("no_active_home")
        "amalia-home:v3:${m.home}:${V2Store.hex(m.secret)}"
    } }

    private suspend fun changeMembership(next: () -> V2Membership?): HomeV2Status = withContext(Dispatchers.IO) {
        gate.withLock {
            val membership = next()
            // Fence dispatch before stopping sockets; persist before making replacement visible.
            stopLocked()
            store.persist(membership)
            synchronized(lock) { mutablePeers.value = emptyList()
                mutableStatus.value = HomeV2Status(store.nodeId, membership?.home) }
            mutableStatus.value
        }
    }

    /** TCP and UDP use 47655 by default, independent of legacy UDP 47654.
     * Starts a lifecycle; listener readiness and bind/retry failures arrive through status.
     */
    suspend fun start(port: Int = 47655): HomeV2Status = withContext(Dispatchers.IO) { gate.withLock {
        v2Require(port in 1..65535)
        if (synchronized(lock) { runtime != null }) return@withLock mutableStatus.value
        scope.coroutineContext.ensureActive()
        val r = Runtime(scope, store.membership, port)
        synchronized(lock) {
            runtime = r
            mutableStatus.value = HomeV2Status(store.nodeId, r.membership?.home, tcpPort = port)
            mutableNetworkStatus.value = HomeV2NetworkStatus()
        }
        r.nsd = HomeV2Nsd(appContext, store.nodeId, r.membership?.home ?: "", platform,
            candidate = { node, home, peerPlatform, source, peerPort ->
                // Convert only a numeric OS-provided address; no DNS or advertised hostname lookup.
                val address = numericAddress(source.hostAddress ?: throw V2Failure("invalid_address"))
                candidate(r, JSONObject().put("v", 3).put("type", "announce").put("node", node)
                    .put("home", home).put("name", "").put("platform", peerPlatform).put("port", peerPort).put("api", 2), address)
            },
            status = { discovering, registered, error -> synchronized(lock) {
                if (runtime === r && r.job.isActive) {
                    mutableNetworkStatus.value = mutableNetworkStatus.value.copy(
                        nsdDiscovering = discovering, nsdRegistered = registered, error = error)
                    mutableStatus.value = mutableStatus.value.copy(discoveryAvailable = r.udpAvailable || discovering)
                }
            } })
        // Cancellation closes blocking IO and Android callbacks even when the parent is cancelled.
        r.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                r.close()
                synchronized(lock) { if (runtime === r) { runtime = null; mutablePeers.value = emptyList()
                    mutableNetworkStatus.value = HomeV2NetworkStatus()
                    mutableStatus.value = mutableStatus.value.copy(running = false, discoveryAvailable = false) } }
            }
        }
        installNetworkCallback(r)
        r.scope.launch { recoveryLoop(r) }
        r.scope.launch { listenerLoop(r) }
        r.refresh.trySend(Unit)
        mutableStatus.value
    } }

    /** Fences dispatch, closes sockets and cancels only this node's supervisor, never caller scope.
     * Already claimed host tasks remain owned by the durable host bridge (poll after restart).
     */
    suspend fun stop() = withContext(Dispatchers.IO) { gate.withLock { stopLocked() } }
    private fun stopLocked() {
        val old = synchronized(lock) { val previous = runtime; runtime = null
            mutableStatus.value = mutableStatus.value.copy(running = false, discoveryAvailable = false)
            mutablePeers.value = emptyList(); mutableNetworkStatus.value = HomeV2NetworkStatus(); previous }
        if (old != null) { old.close(); old.job.cancel() }
        // Do not join under gate: a connection may be waiting for gate to check its generation.
    }
    /** Local lifecycle diagnostic only; never replace membership or expose exception messages. */
    internal fun reportInitializationFailure() = synchronized(lock) {
        mutableStatus.value = mutableStatus.value.copy(running = false, error = "runtime_initialization_failed")
    }

    private fun active(r: Runtime) = synchronized(lock) { runtime === r && r.job.isActive && store.membership === r.membership }
    private fun features(): List<String> = listOf("node.describe") +
        (if (host.homeCapabilityApiVersion >= 2) listOf("capabilities.list", "tasks.submit", "tasks.get") else emptyList()) + extensionOperations.sorted()

    /** Manual fallback accepts numeric IPv4/IPv6 only, no DNS/URLs; address is a route, never trust. */
    suspend fun request(address: String, port: Int, to: String, operation: String, args: JSONObject = JSONObject()): HomeV2Reply =
        withContext(Dispatchers.IO) {
            var sent = false
            var acquired = false
            try {
                v2Id(to); v2Require(port in 1..65535)
                val route = numericAddress(address)
                val r = synchronized(lock) { runtime } ?: throw V2Failure("not_running")
                val m = r.membership ?: throw V2Failure("no_active_home")
                v2Require(active(r), "inactive_membership")
                v2Require(permits.tryAcquire(), "busy"); acquired = true
                val body = JSONObject().put("operation", operation).put("args", args)
                val id = V2Wire.randomHex(16)
                val frame = V2Wire.encrypt(m, store.nodeId, to, id, body)
                val socket = Socket(); r.sockets.add(socket)
                // Caller cancellation must close blocking read/connect/write, not just mark its Job.
                val closer = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { closeQuietly(socket) }
                }
                val timeout = if (operation.startsWith("assistant.")) 125000L else 15000L
                val deadline = r.scope.launch { delay(timeout); closeQuietly(socket) }
                try {
                    socket.soTimeout = timeout.toInt(); socket.connect(InetSocketAddress(route, port), 4000)
                    currentCoroutineContext().ensureActive(); v2Require(active(r), "inactive_membership")
                    sent = true // Any failure after first write may already have executed remotely.
                    V2Wire.write(socket, frame)
                    val response = V2Wire.decrypt(m, store.nodeId, V2Wire.read(socket))
                    v2Require(response.from == to && response.id == id && active(r), "invalid_response")
                    replay.admit("${m.home}:${response.from}:${response.nonce}")
                    v2Keys(response.body, setOf("ok", "data", "error"))
                    val ok = response.body.opt("ok") as? Boolean ?: throw V2Failure("invalid_response")
                    val data = response.body.opt("data") as? JSONObject ?: throw V2Failure("invalid_response")
                    val err = if (response.body.isNull("error")) null else v2String(response.body, "error")
                    v2Require((ok && err == null) || (!ok && err != null && ERROR_CODE.matches(err)), "invalid_response")
                    markAuthenticated(r, to, m.home, route.hostAddress ?: address, port)
                    HomeV2Reply(ok, data, err)
                } finally { closeQuietly(socket); r.sockets.remove(socket); deadline.cancel(); closer.cancel() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                HomeV2Reply(false, error = if (sent) "unknown" else (failure as? V2Failure)?.code ?: "connection_failed")
            } finally { if (acquired) permits.release() }
        }

    private fun installNetworkCallback(r: Runtime) = synchronized(r) {
        if (!active(r) || Build.VERSION.SDK_INT < 24) return@synchronized
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            synchronized(lock) { if (runtime === r) mutableNetworkStatus.value =
                mutableNetworkStatus.value.copy(error = "network_callback_unavailable") }
            return@synchronized
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                // No stop/start or blocking work on Android's callback thread; conflated per runtime.
                if (active(r)) { r.refresh.trySend(Unit); r.retry.trySend(Unit) }
            }
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            r.connectivity = manager; r.callback = callback
            synchronized(lock) { if (runtime === r) mutableNetworkStatus.value =
                mutableNetworkStatus.value.copy(networkCallbackRegistered = true) }
        } catch (_: Exception) {
            synchronized(lock) { if (runtime === r) mutableNetworkStatus.value =
                mutableNetworkStatus.value.copy(error = "network_callback_unavailable") }
        }
    }

    private suspend fun recoveryLoop(r: Runtime) {
        var udpJob: Job? = null
        try {
            for (ignored in r.refresh) {
                delay(750) // Coalesce onAvailable + link changes + listener readiness.
                while (r.refresh.tryReceive().isSuccess) { /* drain */ }
                if (!active(r)) break
                closeQuietly(r.udp)
                udpJob?.cancelAndJoin()
                synchronized(r) {
                    if (active(r)) r.nsd?.restart(r.port.takeIf { r.server?.isClosed == false })
                }
                if (!active(r)) break
                udpJob = r.scope.launch { discoveryLoop(r, r.port) }
            }
        } finally { closeQuietly(r.udp); udpJob?.cancel() }
    }

    /** Retry only transport setup, never an RPC/task. Backoff caps at 30s while joined.
     * A network event can wake the wait; stop or parent cancellation cancels it permanently.
     */
    private suspend fun listenerLoop(r: Runtime) {
        var backoff = 1000L
        while (active(r)) {
            var opened: ServerSocket? = null
            var listening = false
            try {
                val server = ServerSocket().also { opened = it }
                server.reuseAddress = true
                server.bind(InetSocketAddress(r.port), 16)
                server.soTimeout = 1000
                synchronized(r) {
                    if (active(r)) { r.server = server; listening = true }
                }
                if (!listening) break
                synchronized(lock) { if (runtime === r) mutableStatus.value =
                    mutableStatus.value.copy(running = true, error = null) }
                r.refresh.trySend(Unit)
                acceptLoop(r, server)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(lock) { if (runtime === r && r.job.isActive) mutableStatus.value =
                    mutableStatus.value.copy(running = false,
                        error = if (listening) "tcp_listener_failed" else "tcp_bind_failed") }
            } finally {
                closeQuietly(opened)
                synchronized(r) { if (r.server === opened) r.server = null }
                if (listening && active(r)) r.refresh.trySend(Unit)
            }
            if (!active(r)) break
            if (r.membership == null) r.retry.receive()
            else {
                withTimeoutOrNull(backoff) { r.retry.receive() }
                backoff = (backoff * 2).coerceAtMost(30000L)
            }
        }
    }

    private suspend fun acceptLoop(r: Runtime, server: ServerSocket) {
        try {
            while (r.scope.isActive && active(r)) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                if (!permits.tryAcquire()) { closeQuietly(socket); continue }
                r.sockets.add(socket)
                val worker = r.scope.launch {
                    val deadline = r.scope.launch { delay(125000); closeQuietly(socket) }
                    try { socket.soTimeout = 10000; serve(r, socket) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Reject without reflecting unauthenticated data or logging secrets. */ }
                    finally { deadline.cancel() }
                }
                // Completion also runs when cancellation wins before the worker's first instruction.
                worker.invokeOnCompletion { closeQuietly(socket); r.sockets.remove(socket); permits.release() }
            }
        } finally {
            closeQuietly(server)
        }
    }

    private suspend fun serve(r: Runtime, socket: Socket) {
        val m = r.membership ?: return
        val request = V2Wire.decrypt(m, store.nodeId, V2Wire.read(socket))
        replay.admit("${m.home}:${request.from}:${request.nonce}")
        // Also deduplicate request IDs; encrypted duplicates with fresh nonces never redispatch.
        replay.admit("${m.home}:${request.from}:id:${request.id}")
        val reply = try {
            withTimeout(120000) {
                gate.withLock { v2Require(active(r), "inactive_membership") }
                v2Keys(request.body, setOf("operation", "args"))
                val operation = v2String(request.body, "operation")
                val args = request.body.opt("args") as? JSONObject ?: throw V2Failure("invalid_request")
                // Never hold the lifecycle gate across providers/network callbacks:
                // coordinator voting and stop must remain responsive during LLM work.
                v2Require(active(r), "inactive_membership")
                val data = dispatch(operation, args, m.home, request.from)
                JSONObject().put("ok", true).put("data", data).put("error", JSONObject.NULL)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val code = when (failure) {
                is HomeV2Exception -> failure.code.takeIf { ERROR_CODE.matches(it) }
                else -> null
            }
            errorReply(code ?: "handler_failed")
        }
        v2Require(active(r), "inactive_membership")
        val encrypted = try { V2Wire.encrypt(m, store.nodeId, request.from, request.id, reply) }
            catch (failure: V2Failure) { V2Wire.encrypt(m, store.nodeId, request.from, request.id, errorReply(failure.code)) }
        V2Wire.write(socket, encrypted)
    }

    private suspend fun dispatch(operation: String, args: JSONObject, home: String, from: String): JSONObject {
        val requester = "home.v3:$home:$from" // 73 chars; peer cannot select another task namespace.
        when (operation) {
            "node.describe" -> { v2Keys(args, emptySet()); return JSONObject().put("node", store.nodeId)
                .put("name", label).put("platform", platform).put("api", 2).put("features", JSONArray(features())) }
            "capabilities.list" -> {
                requireHost(); v2Keys(args, setOf("cursor", "limit"), emptySet())
                // Leave room for schema-key translation and the wire reply wrapper.
                val page = JSONObject().put("max_bytes", 250000)
                if (args.has("cursor")) { val cursor = v2String(args, "cursor"); v2Require(cursor.length <= 256); page.put("cursor", cursor) }
                val count = if (args.has("limit")) v2Int(args, "limit") else 50
                v2Require(count in 1..100); page.put("page_size", count)
                val result = hostResult(host.homeCapabilities(page.toString()))
                val items = result.optJSONArray("capabilities") ?: throw V2Failure("invalid_host_response")
                val translated = JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    translated.put(JSONObject().put("name", item.getString("name"))
                        .put("description", item.getString("description"))
                        .put("input_schema", item.getJSONObject("parameters")))
                }
                return JSONObject().put("revision", result.getString("revision")).put("capabilities", translated)
                    .put("next_cursor", result.opt("next_cursor") ?: JSONObject.NULL)
            }
            "tasks.submit" -> {
                requireHost(); v2Keys(args, setOf("task_id", "capability", "arguments"))
                val id = v2Id(v2String(args, "task_id")); val capability = v2String(args, "capability")
                v2Require(capability.isNotBlank() && capability.length <= 128)
                val arguments = args.opt("arguments") as? JSONObject ?: throw V2Failure("invalid_request")
                val stable = canonical(arguments).toString()
                v2Require(stable.toByteArray(Charsets.UTF_8).size <= 32768, "arguments_too_large")
                return task(hostResult(host.homeExecuteCapability(requester, id, capability, stable)))
            }
            "tasks.get" -> { requireHost(); v2Keys(args, setOf("task_id"))
                return task(hostResult(host.homeCapabilityTask(requester, v2Id(v2String(args, "task_id"))))) }
            else -> {
                if (operation in extensionOperations) {
                    return extension?.invoke(operation, args, from) ?: throw V2Failure("unsupported_operation")
                }
                throw V2Failure("unsupported_operation")
            }
        }
    }
    private fun requireHost() { v2Require(host.homeCapabilityApiVersion >= 2, "unsupported_operation") }
    private fun hostResult(text: String): JSONObject {
        val result = V2Json.parse(text.toByteArray(Charsets.UTF_8))
        if (result.has("error") && !result.isNull("error")) {
            val code = result.opt("error") as? String
            throw V2Failure(if (code != null && ERROR_CODE.matches(code)) code else "host_error")
        }
        return result
    }
    private fun task(result: JSONObject): JSONObject {
        val state = when (result.getString("state")) {
            "failed" -> "error"
            "queued", "running", "succeeded", "error", "cancelled", "unknown" -> result.getString("state")
            else -> "unknown"
        }
        val code = result.opt("code") as? String
        return JSONObject().put("task_id", v2Id(result.getString("task_id")))
            .put("capability", result.getString("name")).put("state", state)
            .put("result", result.opt("output") ?: JSONObject.NULL)
            .put("error", if (code != null && ERROR_CODE.matches(code)) code else JSONObject.NULL)
            .put("updated_at", result.opt("updated_at") ?: JSONObject.NULL)
    }
    private fun canonical(arguments: JSONObject): JSONObject = JSONObject().also { out ->
        arguments.keys().asSequence().toList().sorted().forEach { key ->
            val value = arguments.get(key)
            v2Require(value !is JSONObject && value !is JSONArray, "invalid_arguments")
            out.put(key, value)
        }
    }

    private suspend fun discoveryLoop(r: Runtime, port: Int) {
        val udp = DatagramSocket(null)
        try {
            currentCoroutineContext().ensureActive()
            udp.reuseAddress = false; udp.broadcast = true; udp.soTimeout = 1000
            udp.bind(InetSocketAddress(47655)); r.udp = udp; r.udpAvailable = true
            if (!active(r)) return
            synchronized(lock) { if (runtime === r) mutableStatus.value = mutableStatus.value.copy(discoveryAvailable = true) }
            var nextAnnounce = 0L
            var rateStart = 0L; var packets = 0
            val buffer = ByteArray(4097)
            while (currentCoroutineContext().isActive && active(r)) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextAnnounce) {
                    if (r.server?.isClosed == false) {
                        val announce = announcement(r, port)
                        broadcastAddresses().forEach { destination -> sendDatagram(udp, announce, destination, 47655) }
                    }
                    nextAnnounce = now + 15000
                    synchronized(lock) { if (runtime === r) mutablePeers.value = mutablePeers.value.filter { now - it.lastSeenElapsedMs < 90000 } }
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try { udp.receive(packet) } catch (_: SocketTimeoutException) { continue }
                if (now - rateStart >= 1000) { rateStart = now; packets = 0 }
                if (++packets > 64 || packet.length > 4096 || !validRoute(packet.address)) continue
                try {
                    val obj = V2Json.parse(packet.data.copyOfRange(packet.offset, packet.offset + packet.length), 4096)
                    v2Require(v2Int(obj, "v") == 3)
                    when (v2String(obj, "type")) {
                        "probe" -> { v2Keys(obj, setOf("v", "type")); if (r.server?.isClosed == false) sendDatagram(udp, announcement(r, port), packet.address, packet.port) }
                        "announce" -> candidate(r, obj, packet.address)
                    }
                } catch (_: Exception) { /* Untrusted discovery packet: discard. */ }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val reporting = currentCoroutineContext().isActive && !udp.isClosed
            synchronized(lock) { if (runtime === r && reporting)
                mutableStatus.value = mutableStatus.value.copy(error = "udp_unavailable") }
        }
        finally { closeQuietly(udp); r.udpAvailable = false
            synchronized(lock) { if (runtime === r) mutableStatus.value = mutableStatus.value.copy(
                discoveryAvailable = mutableNetworkStatus.value.nsdDiscovering) } }
    }
    private fun announcement(r: Runtime, port: Int): ByteArray = JSONObject().put("v", 3).put("type", "announce")
        .put("node", store.nodeId).put("home", r.membership?.home ?: "").put("name", label)
        .put("platform", platform).put("port", port).put("api", 2).toString().toByteArray(Charsets.UTF_8)
    private fun candidate(r: Runtime, obj: JSONObject, source: InetAddress) {
        v2Require(validRoute(source), "invalid_address")
        v2Keys(obj, setOf("v", "type", "node", "home", "name", "platform", "port", "api"))
        val node = v2Id(v2String(obj, "node")); if (node == store.nodeId) return
        val home = v2String(obj, "home"); v2Require(home.isEmpty() || V2_HEX.matches(home))
        val name = v2String(obj, "name"); v2Require(name.length <= 80 && name.none { it.isISOControl() })
        val platform = v2String(obj, "platform"); v2Require(platform in setOf("pc", "android", "android_tv"))
        val port = v2Int(obj, "port"); v2Require(port in 1..65535 && v2Int(obj, "api") == 2)
        val address = source.hostAddress ?: return
        synchronized(lock) {
            if (runtime !== r || !r.job.isActive) return
            val existing = mutablePeers.value.find { it.nodeId == node && it.address == address && it.port == port }
            // Discovery never upgrades, overwrites, or extends lifetime of authenticated knowledge.
            if (existing?.authenticated == true) return
            val rest = mutablePeers.value.filterNot { it.nodeId == node && it.address == address && it.port == port }
            if (rest.size >= 128) return
            mutablePeers.value = rest + HomeV2Peer(node, home, name, platform, address, port, false, SystemClock.elapsedRealtime())
        }
    }
    private fun markAuthenticated(r: Runtime, node: String, home: String, address: String, port: Int) {
        synchronized(lock) {
            if (runtime !== r || !r.job.isActive) return
            val previous = mutablePeers.value.find { it.nodeId == node && it.address == address && it.port == port }
            val rest = mutablePeers.value.filterNot { it.nodeId == node && it.address == address && it.port == port }.takeLast(127)
            // UDP's display metadata stays candidate-only, not authenticated by an arbitrary RPC reply.
            mutablePeers.value = rest + HomeV2Peer(node, home, "", "", address, port, true, SystemClock.elapsedRealtime())
        }
    }
    private fun sendDatagram(socket: DatagramSocket, bytes: ByteArray, address: InetAddress, port: Int) {
        try { socket.send(DatagramPacket(bytes, bytes.size, address, port)) } catch (_: Exception) { /* best effort */ }
    }
    private fun broadcastAddresses(): Set<InetAddress> = try {
        val result = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { iface ->
            iface.interfaceAddresses.mapNotNullTo(result) { it.broadcast }
        }; result
    } catch (_: Exception) { emptySet() }
    private fun numericAddress(text: String): InetAddress {
        v2Require(text.length in 1..100 && (text.matches(Regex("[0-9.]+")) || text.matches(Regex("[0-9a-fA-F:]+(%[a-zA-Z0-9_.-]+)?"))), "invalid_address")
        if (!text.contains(':')) v2Require(text.split('.').size == 4 && text.split('.').all { it.toIntOrNull() in 0..255 }, "invalid_address")
        return InetAddress.getByName(text).also { v2Require(validRoute(it), "invalid_address") }
    }
    private fun validRoute(address: InetAddress) = !address.isAnyLocalAddress && !address.isMulticastAddress &&
        !(address.address.size == 4 && (address.address.last().toInt() and 255) == 255)

    companion object {
        private val ERROR_CODE = Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")
        private val BUILT_INS = setOf("node.describe", "capabilities.list", "tasks.submit", "tasks.get")
        private fun errorReply(code: String) = JSONObject().put("ok", false).put("data", JSONObject()).put("error", code)
        private fun closeQuietly(value: Closeable?) { try { value?.close() } catch (_: Exception) { /* cleanup only */ } }
    }
}
