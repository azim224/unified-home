package org.amalia.unifiedhome.v2

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.util.ArrayDeque

/** Public diagnostics only: no invitation, key, service payload or peer-supplied error text. */
data class HomeV2NetworkStatus(
    val nsdDiscovering: Boolean = false,
    val nsdRegistered: Boolean = false,
    val networkCallbackRegistered: Boolean = false,
    val error: String? = null,
)

/** mDNS supplies routes, never membership or authority. All legacy resolves are serialized,
 * including across discovery restarts: the legacy API cannot cancel an outstanding resolve.
 */
@Suppress("DEPRECATION")
internal class HomeV2Nsd(
    context: Context,
    private val node: String,
    private val home: String,
    private val platform: String,
    private val candidate: (String, String, String, InetAddress, Int) -> Unit,
    private val status: (Boolean, Boolean, String?) -> Unit,
) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var generation = 0L
    private var enabled = false
    private var discovering = false
    private var registered = false
    private var discovery: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized fun restart(port: Int?) {
        stop()
        enabled = true
        val epoch = generation
        val nsd = manager ?: run { report("nsd_unavailable"); return }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = synchronized(this@HomeV2Nsd) {
                if (current(epoch)) { discovering = true; report() }
                else try { nsd.stopServiceDiscovery(this) } catch (_: Exception) { /* already released */ }
            }
            override fun onServiceFound(info: NsdServiceInfo) = synchronized(this@HomeV2Nsd) {
                if (current(epoch) && info.serviceType.trimEnd('.') == TYPE.trimEnd('.') &&
                    queue.size < 32 && queue.none { it.serviceName == info.serviceName }) {
                    queue.addLast(info); resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) = synchronized(this@HomeV2Nsd) {
                if (current(epoch)) queue.removeAll { it.serviceName == info.serviceName }
                Unit
            }
            override fun onDiscoveryStopped(type: String) = synchronized(this@HomeV2Nsd) {
                if (current(epoch)) { discovering = false; discovery = null; report() }
            }
            override fun onStartDiscoveryFailed(type: String, error: Int) = synchronized(this@HomeV2Nsd) {
                if (current(epoch)) { discovering = false; discovery = null; report("nsd_discovery_failed") }
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = synchronized(this@HomeV2Nsd) {
                if (current(epoch)) { discovering = false; report("nsd_stop_failed") }
            }
        }
        discovery = listener
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: Exception) { discovery = null; report("nsd_discovery_failed") }
        if (port == null) return // A failed listener bind must not advertise an unusable endpoint.
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = synchronized(this@HomeV2Nsd) {
                if (current(epoch)) { registered = true; report() }
                else try { nsd.unregisterService(this) } catch (_: Exception) { /* already released */ }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = synchronized(this@HomeV2Nsd) {
                if (current(epoch)) { registration = null; registered = false; report("nsd_registration_failed") }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = synchronized(this@HomeV2Nsd) {
                if (current(epoch)) { registration = null; registered = false; report() }
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = synchronized(this@HomeV2Nsd) {
                if (current(epoch)) { registered = false; report("nsd_unregister_failed") }
            }
        }
        registration = registrationListener
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "amalia-${node.take(16)}"
                serviceType = TYPE
                setPort(port)
                setAttribute("v", "3")
                setAttribute("home", home)
                setAttribute("node", node)
                setAttribute("platform", platform)
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (_: Exception) { registration = null; report("nsd_registration_failed") }
    }

    @Synchronized fun stop() {
        enabled = false; generation++
        queue.clear()
        val oldDiscovery = discovery; discovery = null
        val oldRegistration = registration; registration = null
        discovering = false; registered = false
        if (oldDiscovery != null) try { manager?.stopServiceDiscovery(oldDiscovery) } catch (_: Exception) { /* best effort */ }
        if (oldRegistration != null) try { manager?.unregisterService(oldRegistration) } catch (_: Exception) { /* pending registration is fenced */ }
        // Do not reset resolving: old callbacks must finish before starting another legacy resolve.
        report()
    }

    private fun current(epoch: Long) = enabled && generation == epoch
    private fun report(error: String? = null) = status(discovering, registered, error)
    private fun resolveNext() {
        if (!enabled || resolving || queue.isEmpty()) return
        val info = queue.removeFirst()
        val epoch = generation
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, error: Int) = synchronized(this@HomeV2Nsd) {
                resolving = false
                if (current(epoch)) report("nsd_resolve_failed")
                resolveNext()
            }
            override fun onServiceResolved(service: NsdServiceInfo) = synchronized(this@HomeV2Nsd) {
                resolving = false
                if (current(epoch)) try {
                    fun text(key: String): String {
                        val bytes = service.attributes[key] ?: throw V2Failure("invalid_candidate")
                        v2Require(bytes.size <= 80)
                        return bytes.toString(Charsets.UTF_8)
                    }
                    v2Require(text("v") == "3")
                    val peer = v2Id(text("node"))
                    val peerHome = text("home")
                    v2Require(peerHome.isEmpty() || V2_HEX.matches(peerHome))
                    val peerPlatform = text("platform")
                    v2Require(peerPlatform in setOf("pc", "android", "android_tv"))
                    val address = service.host ?: throw V2Failure("invalid_candidate")
                    v2Require(service.port in 1..65535)
                    if (peer != node) candidate(peer, peerHome, peerPlatform, address, service.port)
                } catch (_: Exception) { /* Untrusted TXT/address: never promote to authenticated peer. */ }
                resolveNext()
            }
        }
        LegacyResolver.enqueue { complete ->
            synchronized(this@HomeV2Nsd) {
                if (!current(epoch)) { resolving = false; complete(); resolveNext() }
                else try {
                    val wrapped = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, error: Int) {
                            try { listener.onResolveFailed(service, error) } finally { complete() }
                        }
                        override fun onServiceResolved(service: NsdServiceInfo) {
                            try { listener.onServiceResolved(service) } finally { complete() }
                        }
                    }
                    manager!!.resolveService(info, wrapped)
                } catch (_: Exception) {
                    resolving = false; report("nsd_resolve_failed"); complete(); resolveNext()
                }
            }
        }
    }

    companion object { const val TYPE = "_amalia-home._tcp." }
}

/** Process-wide serialization also covers stop/start and replacement HomeV2Node instances.
 * No timeout releases the slot: legacy Android has no cancelResolveService. A missing platform
 * callback must stall NSD resolution rather than allow concurrent resolves; UDP/manual still work.
 * Queue bookkeeping runs on main, but performs no socket IO or blocking waits there.
 */
private object LegacyResolver {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<((() -> Unit) -> Unit)>()
    private var busy = false
    fun enqueue(resolve: (() -> Unit) -> Unit) {
        handler.post { pending.addLast(resolve); next() }
    }
    private fun next() {
        if (busy || pending.isEmpty()) return
        busy = true
        val resolve = pending.removeFirst()
        var finished = false
        resolve {
            handler.post {
                if (!finished) { finished = true; busy = false; next() }
            }
        }
    }
}
