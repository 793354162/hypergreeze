package dev.hypergreeze.module

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.hypergreeze.core.EventRecord
import dev.hypergreeze.core.EventType
import dev.hypergreeze.core.Heartbeat
import dev.hypergreeze.core.JournalCodec
import dev.hypergreeze.core.JournalEntry
import dev.hypergreeze.core.JournalRing
import dev.hypergreeze.core.Keys
import dev.hypergreeze.core.Module
import dev.hypergreeze.core.TelemetryCodec

/**
 * Writes the hook side's state back into `Settings.System`, which is the only channel the UI can
 * read without a Binder service. Three documents:
 *
 *  - heartbeat (who am I, which hooks exists, what did I find on this ROM)
 *  - event ring buffer (freeze / thaw / skip / error with GreeZe's own result string)
 *  - log ring buffer (last N lines, for the diagnostics page)
 *
 * All writes are debounced and capped. A Settings write is a Binder round trip into
 * `SettingsProvider`; doing it per hook invocation would be a real performance problem on a
 * hot path that runs on every foreground/background transition.
 */
class ModuleTelemetry(private val context: Context) {

    private val lock = Any()
    private val events = ArrayDeque<EventRecord>(Module.EVENT_RING)
    private var eventsDirty = false
    private var lastHeartbeatAt = 0L
    private var lastLogFlushAt = 0L
    private var writeFailures = 0

    @Volatile
    private var lastHeartbeat: Heartbeat? = null

    /** Ring size, lowered by `global.compactEvents` to keep Settings writes small. */
    @Volatile
    var eventCap: Int = Module.EVENT_RING

    private val journal = JournalRing(Module.JOURNAL_RING)
    private var journalDirty = false

    val heartbeat: Heartbeat? get() = lastHeartbeat

    // ------------------------------------------------------------------ side-effect ledger

    fun loadExistingJournal() {
        try {
            val existing = JournalCodec.decode(readString(Keys.JOURNAL))
            synchronized(lock) {
                journal.clear()
                existing.forEach { journal.add(it) }
            }
        } catch (t: Throwable) {
            Logx.w("loadExistingJournal failed", t)
        }
    }

    fun journalAdd(entry: JournalEntry) {
        synchronized(lock) {
            journal.add(entry)
            journalDirty = true
        }
    }

    fun journalEntries(): List<JournalEntry> = synchronized(lock) { journal.snapshot() }

    fun journalUndoable(): Int = synchronized(lock) { journal.undoableCount }

    fun flushJournal() {
        val encoded = synchronized(lock) {
            if (!journalDirty) return
            journalDirty = false
            JournalCodec.encode(journal.snapshot())
        }
        writeString(Keys.JOURNAL, encoded)
    }

    fun clearJournal() {
        synchronized(lock) {
            journal.clear()
            journalDirty = false
        }
        writeString(Keys.JOURNAL, "[]")
    }

    fun loadExistingEvents() {
        try {
            val existing = TelemetryCodec.decodeEvents(readString(Keys.EVENTS))
            synchronized(lock) {
                events.clear()
                events.addAll(existing.takeLast(Module.EVENT_RING))
            }
        } catch (t: Throwable) {
            Logx.w("loadExistingEvents failed", t)
        }
    }

    /** Publishes a heartbeat, at most once per [HEARTBEAT_MIN_INTERVAL_MS] unless forced. */
    fun publishHeartbeat(hb: Heartbeat, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastHeartbeatAt < HEARTBEAT_MIN_INTERVAL_MS) {
            lastHeartbeat = hb
            return
        }
        lastHeartbeatAt = now
        lastHeartbeat = hb
        writeString(Keys.HEARTBEAT, TelemetryCodec.encodeHeartbeat(hb))
    }

    fun record(event: EventRecord) {
        synchronized(lock) {
            events.addLast(event)
            while (events.size > eventCap) events.removeFirst()
            eventsDirty = true
        }
    }

    fun record(
        type: String,
        uid: Int = 0,
        pkg: String? = null,
        reason: Int = 0,
        result: String? = null,
        detail: String? = null,
    ) {
        record(
            EventRecord(
                ts = System.currentTimeMillis(),
                type = type,
                pkg = pkg,
                uid = uid,
                reason = reason,
                result = result,
                detail = detail,
            ),
        )
    }

    fun flushEvents() {
        val encoded = synchronized(lock) {
            if (!eventsDirty) return
            eventsDirty = false
            TelemetryCodec.encodeEvents(events.toList())
        }
        writeString(Keys.EVENTS, encoded)
    }

    fun clearEvents() {
        synchronized(lock) {
            events.clear()
            eventsDirty = false
        }
        writeString(Keys.EVENTS, "[]")
        Logx.i("event ring cleared")
    }

    fun eventCount(): Int = synchronized(lock) { events.size }

    fun flushLogs(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLogFlushAt < LOG_FLUSH_INTERVAL_MS) return
        lastLogFlushAt = now
        writeString(Keys.LOGS, TelemetryCodec.encodeLogs(Logx.snapshot()))
    }

    // ------------------------------------------------------------------ raw settings access

    fun readString(key: String): String? = try {
        Settings.System.getString(context.contentResolver, key)
    } catch (t: Throwable) {
        Logx.w("read $key failed", t)
        null
    }

    fun readInt(key: String, def: Int = 0): Int =
        readString(key)?.trim()?.toIntOrNull() ?: def

    fun writeString(key: String, value: String?): Boolean = try {
        val ok = if (value == null) {
            Settings.System.putString(context.contentResolver, key, null)
        } else {
            Settings.System.putString(context.contentResolver, key, value)
        }
        if (!ok) {
            writeFailures++
            if (writeFailures <= 3) Logx.w("settings write rejected for $key")
        }
        ok
    } catch (t: Throwable) {
        writeFailures++
        if (writeFailures <= 3) Logx.w("settings write failed for $key", t)
        false
    }

    fun writeInt(key: String, value: Int): Boolean = writeString(key, value.toString())

    fun writeFlag(key: String, value: Boolean): Boolean = writeString(key, if (value) "1" else "0")

    fun readFlag(key: String): Boolean = readString(key)?.trim() == "1"

    val failures: Int get() = writeFailures

    companion object {
        private const val HEARTBEAT_MIN_INTERVAL_MS = 5_000L
        private const val LOG_FLUSH_INTERVAL_MS = 15_000L
    }
}
