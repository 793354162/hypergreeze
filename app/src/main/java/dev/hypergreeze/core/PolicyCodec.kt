package dev.hypergreeze.core

import org.json.JSONObject

/**
 * JSON <-> [Policy]. Hand-written instead of kotlinx.serialization on purpose: the same code runs
 * inside `system_server` where we want zero extra class loading, and hand-edited policies pushed
 * with `adb shell settings put system greeze_ctl_policy_v1 '<json>'` must keep working.
 *
 * Decoding is total: any malformed input yields [Policy.EMPTY] (i.e. "everything follows the
 * system"), never an exception, never a partially applied policy.
 */
object PolicyCodec {
    const val VERSION = 2

    fun encode(policy: Policy): String {
        val root = JSONObject()
        root.put("v", policy.version)
        root.put("rev", policy.revision)
        root.put("ts", policy.updatedAt)
        root.put("global", encodeGlobal(policy.global))
        val apps = JSONObject()
        for ((pkg, app) in policy.apps) {
            apps.put(pkg, encodeApp(app))
        }
        root.put("apps", apps)
        return root.toString()
    }

    fun decode(raw: String?): Policy {
        if (raw.isNullOrBlank()) return Policy.EMPTY
        return try {
            val root = JSONObject(raw)
            Policy(
                version = root.oInt("v") ?: VERSION,
                revision = root.oLong("rev") ?: 0L,
                updatedAt = root.oLong("ts") ?: 0L,
                global = root.oObj("global")?.let(::decodeGlobal) ?: GlobalPolicy(),
                apps = decodeApps(root.oObj("apps")),
            )
        } catch (_: Throwable) {
            Policy.EMPTY
        }
    }

    private fun decodeApps(obj: JSONObject?): Map<String, AppPolicy> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, AppPolicy>(obj.length())
        val it = obj.keys()
        while (it.hasNext()) {
            val pkg = it.next()
            val entry = obj.optJSONObject(pkg) ?: continue
            val policy = decodeApp(entry)
            if (policy.isEffective) out[pkg] = policy
        }
        return out
    }

    fun encodeApp(app: AppPolicy): JSONObject = JSONObject()
        .put("mode", app.mode.wire)
        .putOpt("delayMs", app.delayMs.takeIf { it != 0L })
        .putOpt("thawCooldownMs", app.thawCooldownMs.takeIf { it != 0L })
        .putOpt("keep", app.maskKeep.takeIf { it != 0 })
        .putOpt("drop", app.maskDrop.takeIf { it != 0 })
        .putOpt("processes", encodeProcesses(app.processes))
        .putOpt("resources", encodeResources(app.resources))
        .putOpt("thawOn", encodeThaw(app.thawOn))
        .putOpt("note", app.note.takeIf { it.isNotBlank() })

    fun decodeApp(obj: JSONObject): AppPolicy = AppPolicy(
        mode = FreezeMode.fromWire(obj.oStr("mode")),
        delayMs = (obj.oLong("delayMs") ?: 0L).coerceIn(0L, 600_000L),
        thawCooldownMs = (obj.oLong("thawCooldownMs") ?: 0L).coerceIn(0L, 3_600_000L),
        maskKeep = obj.oInt("keep") ?: 0,
        maskDrop = stripLocked(obj.oInt("drop") ?: 0),
        processes = decodeProcesses(obj.oObj("processes")),
        resources = decodeResources(obj.oObj("resources")),
        thawOn = decodeThaw(obj.oObj("thawOn")),
        note = obj.oStr("note").orEmpty(),
    )

    /** Bits in [SceneBit.LOCKED_DROP] can never be dropped, whatever the stored document says. */
    private fun stripLocked(mask: Int): Int {
        var out = mask
        for (bit in SceneBit.LOCKED_DROP) out = out and bit.value.inv()
        return out
    }

    private fun encodeProcesses(value: Map<String, FreezeMode>): JSONObject? {
        if (value.isEmpty()) return null
        val obj = JSONObject()
        for ((name, mode) in value) if (mode != FreezeMode.FOLLOW) obj.put(name, mode.wire)
        return if (obj.length() == 0) null else obj
    }

    private fun decodeProcesses(obj: JSONObject?): Map<String, FreezeMode> {
        if (obj == null || obj.length() == 0) return emptyMap()
        val out = LinkedHashMap<String, FreezeMode>(obj.length())
        val it = obj.keys()
        while (it.hasNext()) {
            val name = it.next()
            val mode = FreezeMode.fromWire(obj.oStr(name))
            if (mode != FreezeMode.FOLLOW) out[name] = mode
        }
        return out
    }

    private fun encodeResources(value: ResourceSwitches): JSONObject? {
        if (value.isEmpty) return null
        return JSONObject()
            .putOpt("net", value.net)
            .putOpt("alarm", value.alarm)
            .putOpt("job", value.job)
            .putOpt("wakelock", value.wakelock)
            .putOpt("broadcast", value.broadcast)
            .putOpt("cpu", value.cpu)
    }

    private fun decodeResources(obj: JSONObject?): ResourceSwitches {
        if (obj == null) return ResourceSwitches()
        return ResourceSwitches(
            net = obj.oBool("net"),
            alarm = obj.oBool("alarm"),
            job = obj.oBool("job"),
            wakelock = obj.oBool("wakelock"),
            broadcast = obj.oBool("broadcast"),
            cpu = obj.oBool("cpu"),
        )
    }

    private fun encodeThaw(value: ThawTriggers): JSONObject? {
        if (value.isEmpty) return null
        return JSONObject()
            .putOpt("screenOn", value.screenOn)
            .putOpt("netChange", value.netChange)
            .putOpt("notification", value.notification)
            .putOpt("audioFocus", value.audioFocus)
            .putOpt("charging", value.charging)
            .putOpt("externalWake", value.externalWake)
    }

    private fun decodeThaw(obj: JSONObject?): ThawTriggers {
        if (obj == null) return ThawTriggers()
        return ThawTriggers(
            screenOn = obj.oBool("screenOn"),
            netChange = obj.oBool("netChange"),
            notification = obj.oBool("notification"),
            audioFocus = obj.oBool("audioFocus"),
            charging = obj.oBool("charging"),
            externalWake = obj.oBool("externalWake"),
        )
    }

    fun encodeGlobal(value: GlobalPolicy): JSONObject = JSONObject()
        .put("enabled", value.enabled)
        .put("forceFreezeOnBackground", value.forceFreezeOnBackground)
        .put("audit", value.audit)
        .put("patchLists", value.patchLists)
        .put("compactEvents", value.compactEvents)
        .put("filterPolarityInverted", value.filterPolarityInverted)
        .put("uiExpert", value.uiExpert)
        .putOpt("defaultDelayMs", value.defaultDelayMs.takeIf { it != 0L })
        .putOpt("applyFreezeBinder", value.applyFreezeBinder)

    fun decodeGlobal(obj: JSONObject): GlobalPolicy = GlobalPolicy(
        enabled = obj.oBool("enabled") ?: true,
        forceFreezeOnBackground = obj.oBool("forceFreezeOnBackground") ?: true,
        audit = obj.oBool("audit") ?: true,
        patchLists = obj.oBool("patchLists") ?: false,
        compactEvents = obj.oBool("compactEvents") ?: false,
        filterPolarityInverted = obj.oBool("filterPolarityInverted") ?: false,
        uiExpert = obj.oBool("uiExpert") ?: false,
        defaultDelayMs = (obj.oLong("defaultDelayMs") ?: 0L).coerceIn(0L, 600_000L),
        applyFreezeBinder = obj.oBool("applyFreezeBinder"),
    )
}
