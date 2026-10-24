package dev.hypergreeze.core

import org.json.JSONObject

/**
 * One-shot command posted by the UI into `Settings.System` and executed by the hook side.
 *
 * The UI has no Binder access to `system_server` (and must not need one), so actions that are not
 * a policy write — thaw everything, freeze one app now, trigger a GreeZe mode — travel as a
 * monotonically numbered command. The hook side remembers the last executed [seq] in its
 * heartbeat, which makes re-delivery after a reboot harmless.
 */
data class Command(
    val seq: Long,
    val op: String,
    val pkg: String? = null,
    val uid: Int = 0,
    val arg: String? = null,
) {
    companion object {
        const val OP_THAW_ALL = "thawAll"
        const val OP_THAW_UID = "thawUid"
        const val OP_FREEZE_UID = "freezeUid"
        const val OP_FREEZE_PKG = "freezePkg"
        const val OP_QUICK_MODE = "quickMode"
        const val OP_RESET_CGROUP = "resetCgroup"
        const val OP_CLEAR_EVENTS = "clearEvents"
        const val OP_CLEAR_SAFE_MODE = "clearSafeMode"
        const val OP_APPLY_LISTS = "applyLists"
        const val OP_SET_BINDER = "setFreezeBinder"

        /** Undo every journaled side effect, thaw everything, stop re-applying. Keeps the policy. */
        const val OP_REVERT_ALL = "revertAll"

        /** Revert + delete every Settings key and system property this module owns. For uninstall. */
        const val OP_CLEAR_DATA = "clearData"

        val ALL_OPS = listOf(
            OP_THAW_ALL, OP_THAW_UID, OP_FREEZE_UID, OP_FREEZE_PKG, OP_QUICK_MODE,
            OP_RESET_CGROUP, OP_CLEAR_EVENTS, OP_CLEAR_SAFE_MODE, OP_APPLY_LISTS, OP_SET_BINDER,
            OP_REVERT_ALL, OP_CLEAR_DATA,
        )
    }
}

object CommandCodec {
    fun encode(command: Command): String = JSONObject()
        .put("seq", command.seq)
        .put("op", command.op)
        .putOpt("pkg", command.pkg)
        .putOpt("uid", command.uid.takeIf { it != 0 })
        .putOpt("arg", command.arg)
        .toString()

    fun decode(raw: String?): Command? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val op = obj.oStr("op") ?: return null
            if (op !in Command.ALL_OPS) return null
            Command(
                seq = obj.oLong("seq") ?: 0L,
                op = op,
                pkg = obj.oStr("pkg"),
                uid = obj.oInt("uid") ?: 0,
                arg = obj.oStr("arg"),
            )
        } catch (_: Throwable) {
            null
        }
    }
}
