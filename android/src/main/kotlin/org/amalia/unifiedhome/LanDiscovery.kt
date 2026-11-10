package org.amalia.unifiedhome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Bounded bootstrap on directly connected private IPv4 networks, never public ranges.
 * Probes carry only authenticated presence, never RPC; dedicated workers cannot starve commands.
 */
internal object LanDiscovery {
    private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "home-discovery-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private data class Subnet(val local: Long, val first: Long, val count: Long)

    private fun subnets(): List<Subnet> = try {
        val result = mutableListOf<Subnet>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback || iface.isPointToPoint || iface.isVirtual) continue
            iface.interfaceAddresses.forEach { entry ->
                val address = entry.address as? Inet4Address ?: return@forEach
                val prefix = entry.networkPrefixLength.toInt()
                // A faulty /0 route must never authorize scanning outside private LANs.
                val minimum = when (address.address[0].toInt() and 255) {
                    10 -> 8
                    172 -> 12
                    192 -> 16
                    else -> 32
                }
                if (!address.isSiteLocalAddress || prefix !in minimum..30) return@forEach
                val local = address.address.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
                val mask = (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
                result.add(Subnet(local, (local and mask) + 1, (1L shl (32 - prefix)) - 2))
            }
        }
        result.distinctBy { it.first to it.count }.take(8)
    } catch (_: java.net.SocketException) { emptyList() }

    fun candidates(offset: Long): List<String> {
        val networks = subnets()
        if (networks.isEmpty()) return emptyList()
        val result = linkedSetOf<String>()
        // Round-robin interfaces so a VPN/virtual route cannot monopolize the sweep budget.
        for (index in 0 until 254) {
            val network = networks[index % networks.size]
            val address = network.first + ((offset / networks.size + index / networks.size) % network.count)
            if (address == network.local) continue
            result.add((3 downTo 0).joinToString(".") { ((address ushr (it * 8)) and 255).toString() })
        }
        return result.toList()
    }

    fun broadcasts(): Set<String> = try {
        val result = linkedSetOf("255.255.255.255")
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isUp && !iface.isLoopback) iface.interfaceAddresses.forEach {
                (it.broadcast as? Inet4Address)?.hostAddress?.let(result::add)
            }
        }
        result
    } catch (_: java.net.SocketException) { setOf("255.255.255.255") }

    suspend fun probe(hosts: List<String>, port: Int, payload: ByteArray, current: () -> Boolean) =
        probe(hosts.map { it to port }, payload, current, 400)

    suspend fun probe(endpoints: List<Pair<String, Int>>, payload: ByteArray,
        current: () -> Boolean, connectTimeout: Int) = withContext(Dispatchers.IO) {
            coroutineScope {
                endpoints.chunked(8).forEach { group ->
                    currentCoroutineContext().ensureActive()
                    if (!current()) return@coroutineScope
                    group.map { (host, port) -> async {
                        try {
                            Socket().use { socket ->
                                val deadline = deadlines.schedule({ runCatching { socket.close() } },
                                    (connectTimeout + 2_000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                                try {
                                    socket.connect(InetSocketAddress(InetAddress.getByName(host), port), connectTimeout)
                                    if (!current()) return@use
                                    DataOutputStream(socket.getOutputStream()).apply {
                                        writeInt(payload.size)
                                        write(payload)
                                        flush()
                                    }
                                } finally { deadline.cancel(false) }
                            }
                        } catch (_: java.io.IOException) {
                            // Closed/filtered ports are normal during bootstrap, not service failures.
                        }
                    } }.awaitAll()
                }
            }
        }
}
