package org.amalia.unifiedhome

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One authenticated envelope per connection. Delivery failure is never retried (outcome unknown).
 * Separate bounded pools prevent slow inbound clients from starving outbound RPC/results.
 */
internal class ReliableTransport(port: Int, private val receive: (ByteArray, String) -> Unit) {
    private val factory = ThreadFactory { task -> Thread(task, "home-tcp").apply { isDaemon = true } }
    private fun pool() = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(16), factory, ThreadPoolExecutor.AbortPolicy())
    private val inbound = pool()
    private val outbound = pool()
    private val timer = ScheduledThreadPoolExecutor(1, factory).apply { removeOnCancelPolicy = true }
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val server = ServerSocket()
    @Volatile private var closed = false
    val running: Boolean get() = !closed && !server.isClosed && server.isBound

    init {
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            factory.newThread { acceptLoop() }.start()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun acceptLoop() {
        try {
            while (running) {
                val socket = server.accept()
                connections.add(socket)
                try {
                    // Includes queue time and the entire frame, not only individual read calls.
                    val deadline = timer.schedule({ closeSocket(socket) }, 5, TimeUnit.SECONDS)
                    try {
                        inbound.execute {
                            try {
                                socket.use {
                                    it.soTimeout = 5_000
                                    val input = DataInputStream(it.getInputStream())
                                    val length = input.readInt().toLong() and 0xffff_ffffL
                                    require(length in 1L..HomeLink.MAX_PACKET.toLong())
                                    val payload = ByteArray(length.toInt())
                                    input.readFully(payload)
                                    receive(payload, it.inetAddress.hostAddress.orEmpty())
                                }
                            } catch (_: Exception) {
                                // Truncated/oversized/slow or unauthenticated frames are discarded.
                            } finally {
                                deadline.cancel(false)
                                closeSocket(socket)
                            }
                        }
                    } catch (rejected: RejectedExecutionException) {
                        deadline.cancel(false)
                        closeSocket(socket)
                    }
                } catch (_: RejectedExecutionException) { closeSocket(socket) }
            }
        } catch (_: Exception) {
            // Mesh supervisor notices the closed listener and rebinds automatically.
        } finally { close() }
    }

    /** True means queued, NOT acknowledged or executed. Never fall back to UDP on failure. */
    fun send(host: String, port: Int, payload: ByteArray, current: () -> Boolean): Boolean {
        if (!running || payload.isEmpty() || payload.size > HomeLink.MAX_PACKET) return false
        val socket = Socket()
        connections.add(socket)
        if (!running) { closeSocket(socket); return false }
        var deadline: java.util.concurrent.ScheduledFuture<*>? = null
        return try {
            // Queue delay is included so an expired request is not sent much later under load.
            val expiry = timer.schedule({ closeSocket(socket) }, 8, TimeUnit.SECONDS)
            deadline = expiry
            outbound.execute {
                try {
                    // current() may wait for the mesh monitor; recheck closure after it returns.
                    if (!current() || !running || socket.isClosed) return@execute
                    socket.use {
                        it.connect(InetSocketAddress(host, port), 3_000)
                        // Expired/cancelled arbitration must never start delayed remote work.
                        if (!current() || !running || socket.isClosed) return@execute
                        it.soTimeout = 5_000
                        val output = DataOutputStream(it.getOutputStream())
                        output.writeInt(payload.size)
                        output.write(payload)
                        output.flush()
                    }
                } catch (_: Exception) {
                    // A partial write may have executed remotely. Pending RPC expires as unknown.
                } finally {
                    expiry.cancel(false)
                    closeSocket(socket)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            deadline?.cancel(false)
            closeSocket(socket)
            false
        }
    }

    private fun closeSocket(socket: Socket) {
        connections.remove(socket)
        runCatching { socket.close() }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        inbound.shutdownNow()
        outbound.shutdownNow()
        timer.shutdownNow()
        connections.toList().forEach { closeSocket(it) }
    }
}
