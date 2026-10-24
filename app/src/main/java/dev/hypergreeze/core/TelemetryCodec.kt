package dev.hypergreeze.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec for the two "hook -> UI" documents. Kept in `core` so both processes agree on the shape
 * and the UI can decode a document written by an older hook generation.
 */
object TelemetryCodec {

    // ------------------------------------------------------------------ heartbeat

    fun encodeHeartbeat(hb: Heartbeat): String {
        val root = JSONObject()
        root.put("ts", hb.ts)
        root.put("moduleV", hb.moduleVersion)
        root.put("api", hb.apiVersion)
        root.put("fw", hb.frameworkName)
        root.put("fwV", hb.frameworkVersion)
        root.put("fwProps", hb.frameworkProperties)
        root.put("proc", hb.processName)
        root.put("build", hb.buildFingerprint)
        root.put("greeze", hb.greezePresent)
        root.putOpt("greezeClass", hb.greezeClass)
        root.put("instance", hb.instanceOk)
        root.put("enabled", hb.policyEnabled)
        root.put("safeMode", hb.safeMode)
        root.putOpt("safeReason", hb.safeModeReason)
        root.put("bootGuard", hb.bootGuard)
        root.put("revision", hb.revision)
        root.put("lastSeq", hb.lastCommandSeq)
        root.put("journal", hb.journalUndoable)
        root.put("suppressed", hb.suppressed)

        val hooks = JSONArray()
        for (hook in hb.hooks) {
            hooks.put(
                JSONObject()
                    .put("id", hook.id)
                    .put("target", hook.target)
                    .put("state", hook.state)
                    .put("hits", hook.hits)
                    .put("errors", hook.errors)
                    .putOpt("note", hook.note),
            )
        }
        root.put("hooks", hooks)

        val caps = JSONObject()
        for ((key, value) in hb.capabilities) caps.put(key, value)
        root.put("caps", caps)
        return root.toString()
    }

    fun decodeHeartbeat(raw: String?): Heartbeat? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            val hooks = ArrayList<HookStatus>()
            root.oArr("hooks")?.objects()?.forEach { item ->
                hooks.add(
                    HookStatus(
                        id = item.oStr("id").orEmpty(),
                        target = item.oStr("target").orEmpty(),
                        state = item.oStr("state") ?: HookState.MISSING,
                        hits = item.oLong("hits") ?: 0L,
                        errors = item.oLong("errors") ?: 0L,
                        note = item.oStr("note"),
                    ),
                )
            }
            val caps = LinkedHashMap<String, Boolean>()
            root.oObj("caps")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    caps[key] = obj.optBoolean(key, false)
                }
            }
            Heartbeat(
                ts = root.oLong("ts") ?: 0L,
                moduleVersion = root.oStr("moduleV").orEmpty(),
                apiVersion = root.oInt("api") ?: 0,
                frameworkName = root.oStr("fw").orEmpty(),
                frameworkVersion = root.oStr("fwV").orEmpty(),
                frameworkProperties = root.oLong("fwProps") ?: 0L,
                processName = root.oStr("proc").orEmpty(),
                buildFingerprint = root.oStr("build").orEmpty(),
                greezePresent = root.oBool("greeze") ?: false,
                greezeClass = root.oStr("greezeClass"),
                instanceOk = root.oBool("instance") ?: false,
                policyEnabled = root.oBool("enabled") ?: false,
                safeMode = root.oBool("safeMode") ?: false,
                safeModeReason = root.oStr("safeReason"),
                bootGuard = root.oInt("bootGuard") ?: 0,
                revision = root.oLong("revision") ?: 0L,
                lastCommandSeq = root.oLong("lastSeq") ?: 0L,
                journalUndoable = root.oInt("journal") ?: 0,
                suppressed = root.oBool("suppressed") ?: false,
                hooks = hooks,
                capabilities = caps,
            )
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ events

    fun encodeEvents(events: List<EventRecord>): String {
        val array = JSONArray()
        for (event in events) {
            array.put(
                JSONObject()
                    .put("ts", event.ts)
                    .put("t", event.type)
                    .putOpt("pkg", event.pkg)
                    .put("uid", event.uid)
                    .putOpt("r", event.reason.takeIf { it != 0 })
                    .putOpt("res", event.result)
                    .putOpt("d", event.detail),
            )
        }
        return array.toString()
    }

    fun decodeEvents(raw: String?): List<EventRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            JSONArray(raw).objects().map { item ->
                EventRecord(
                    ts = item.oLong("ts") ?: 0L,
                    type = item.oStr("t") ?: EventType.INFO,
                    pkg = item.oStr("pkg"),
                    uid = item.oInt("uid") ?: 0,
                    reason = item.oInt("r") ?: 0,
                    result = item.oStr("res"),
                    detail = item.oStr("d"),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------ logs

    fun encodeLogs(lines: List<String>): String {
        val array = JSONArray()
        for (line in lines) array.put(line)
        return array.toString()
    }

    fun decodeLogs(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            JSONArray(raw).strings()
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
