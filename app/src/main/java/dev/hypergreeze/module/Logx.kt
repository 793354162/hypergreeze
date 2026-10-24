package dev.hypergreeze.module

import dev.hypergreeze.core.Module

/**
 * Log sink for the hook side. Everything is mirrored to the Xposed log (so it shows up in
 * LSPosed's log viewer) and into a small in-memory ring buffer that the diagnostics page can
 * read back through `Settings.System`.
 *
 * Never throws: a logging failure must never take system_server down.
 */
object Logx {
    private val ring = ArrayDeque<String>(Module.LOG_RING)
    private var sink: ((Int, String, String, Throwable?) -> Unit)? = null
    private var dropped = 0

    fun attach(sink: (Int, String, String, Throwable?) -> Unit) {
        this.sink = sink
    }

    fun i(msg: String) = log(4, msg, null)
    fun w(msg: String) = log(5, msg, null)
    fun w(msg: String, t: Throwable) = log(5, msg, t)
    fun e(msg: String, t: Throwable? = null) = log(6, msg, t)

    private fun log(level: Int, msg: String, t: Throwable?) {
        val line = buildString {
            append(levelTag(level)).append(' ').append(msg)
            if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
        }
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > Module.LOG_RING) {
                ring.removeFirst()
                dropped++
            }
        }
        try {
            sink?.invoke(level, Module.TAG, msg, t)
        } catch (_: Throwable) {
            // framework gone; nothing sensible to do
        }
    }

    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }

    fun since(count: Int): List<String> = synchronized(ring) { ring.toList().takeLast(count) }

    private fun levelTag(level: Int): String = when (level) {
        2 -> "V"
        3 -> "D"
        4 -> "I"
        5 -> "W"
        else -> "E"
    }
}
