package com.bastet.notifybridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Delivery endpoint abstraction. */
interface Sink {
    val name: String
    fun start()
    fun stop()
    fun send(line: String)
}

/** Debug mirror: anything routed here lands in logcat, no socket. */
class LogSink(override val name: String) : Sink {
    override fun start() {}
    override fun stop() {}
    override fun send(line: String) {
        android.util.Log.i("notifybridge", "sink[$name] $line")
    }
}

/**
 * One abstract-namespace Unix socket transport, one reconnect loop thread
 * each. On connect the caller replays its live state [onConnect]. The
 * socket is strictly one-way: this app only writes, it never reads a
 * consumer line (no command channel exists). Send is fire-and-forget:
 * while the loop is away the line is dropped, exactly like the old single
 * socket, per sink.
 */
class SocketSink(
    override val name: String,
    private val sockName: String,
    private val onConnect: (Sink) -> Unit
) : Sink {

    @Volatile
    private var sock: LocalSocket? = null
    @Volatile
    internal var connected = false
    private var thread: Thread? = null
    private val lock = Object()

    override fun start() {
        synchronized(lock) {
            if (thread?.isAlive == true) return
            val t = Thread({ loop() }, "nls-sock-$sockName")
            t.isDaemon = true
            t.start()
            thread = t
        }
    }

    override fun stop() {
        synchronized(lock) {
            thread?.interrupt()
            thread = null
        }
        sock?.let { s -> runCatching { s.close() } }
        sock = null
    }

    override fun send(line: String) {
        val s = sock ?: return          // drop lines while reconnecting
        try {
            s.outputStream.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            s.outputStream.flush()
        } catch (_: Exception) {
            // stale write: the loop thread notices EOF and cleans up
        }
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            val ls = try {
                LocalSocket().also {
                    it.connect(
                        LocalSocketAddress(sockName, LocalSocketAddress.Namespace.ABSTRACT)
                    )
                    it.soTimeout = 0
                }
            } catch (_: Exception) {
                sleep(2000)
                continue
            }
            sock = ls
            connected = true
            android.util.Log.i("notifybridge", "sink[$name]: connected to $sockName")
            try {
                onConnect(this)
                drain(ls)
            } finally {
                connected = false
                if (sock === ls) sock = null
                android.util.Log.i("notifybridge", "sink[$name]: closed, reconnecting")
                sleep(1000)
            }
        }
    }

    /** Block until EOF; a read timeout just means "no traffic". Lines are
     *  consumed and dropped - this app has no consumer command channel, the
     *  read exists only to notice the peer closing for the reconnect loop. */
    private fun drain(ls: LocalSocket) {
        try {
            val r = BufferedReader(InputStreamReader(ls.inputStream))
            while (true) {
                val line = try {
                    r.readLine()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                if (line == null) break
            }
        } catch (_: Exception) {
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/** Holds every configured sink by rule target name. Rebuilt on reload. */
class SinkRegistry(
    config: BridgeConfig,
    private val onSocketConnect: (Sink) -> Unit
) {

    private val sinks = LinkedHashMap<String, Sink>()

    init {
        for (cfg in config.sinks) {
            val s: Sink = if (cfg.isSocket)
                SocketSink(cfg.name, cfg.name, onSocketConnect)
            else
                LogSink(cfg.name.ifEmpty { "log" })
            sinks[cfg.name.ifEmpty { "log" }] = s
        }
    }

    operator fun get(name: String): Sink? = sinks[name]

    /** Any socket sink live right now (used for the SIGUSR2 fallback). */
    fun anySocketConnected(): Boolean =
        sinks.values.filterIsInstance<SocketSink>().any { it.connected }

    fun startAll() = sinks.values.forEach { it.start() }

    fun stopAll() = sinks.values.forEach { it.stop() }
}