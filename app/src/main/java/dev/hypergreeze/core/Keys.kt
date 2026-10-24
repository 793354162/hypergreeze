package dev.hypergreeze.core

/**
 * Single source of truth for every string that crosses the UI <-> system_server boundary.
 *
 * The channel itself is plain [android.provider.Settings.System]: keys are written by the app
 * (needs the `WRITE_SETTINGS` appop) and read by the hook running inside `system_server`.
 * See docs/POLICY_SCHEMA.md §"Why Settings.System".
 */
object Keys {
    /** Policy document written by the UI, read by the hook side. JSON, see [PolicyCodec]. */
    const val POLICY = "greeze_ctl_policy_v1"

    /** Module/hook status snapshot written by the hook side, read by the UI. JSON. */
    const val HEARTBEAT = "greeze_ctl_heartbeat_v1"

    /** Freeze/thaw event ring buffer written by the hook side, read by the UI. JSON array. */
    const val EVENTS = "greeze_ctl_events_v1"

    /** Hook-side log ring buffer, JSON array of strings. */
    const val LOGS = "greeze_ctl_logs_v1"

    /** Boot-loop guard counter, plain integer string. */
    const val BOOT_GUARD = "greeze_ctl_boot_guard_v1"

    /** Manual safe-mode request flag written by the UI ("1"); cleared by the hook side. */
    const val SAFE_MODE_REQUEST = "greeze_ctl_safe_mode_v1"

    /** Human readable reason for the last safe-mode entry. */
    const val SAFE_MODE_REASON = "greeze_ctl_safe_mode_reason_v1"

    /** One-shot command posted by the UI, executed by the hook side. See [Command]. */
    const val COMMAND = "greeze_ctl_command_v1"

    /** Broadcast used as a best-effort fast path; polling is the guaranteed path. */
    const val ACTION_STATE_CHANGED = "dev.hypergreeze.action.STATE_CHANGED"

    val ALL: List<String> = listOf(
        POLICY, HEARTBEAT, EVENTS, LOGS, BOOT_GUARD, SAFE_MODE_REQUEST, SAFE_MODE_REASON, COMMAND,
    )
}

object Module {
    const val PKG = "dev.hypergreeze"
    const val NAME = "HyperGreeze"
    const val TAG = "HyperGreeze"

    /** Value returned from `freezeUid` when the module refuses to freeze an app. */
    const val RESULT_SKIP_BY_MOD = "SKIP_BY_MOD"

    /** Tag handed to GreeZe so its own logs/history attribute the freeze to us. */
    const val FREEZE_TAG = "hypergreeze"

    /** `reason` int used for module-initiated freezes (GreeZe has no matching constant). */
    const val REASON_MOD = 1000

    const val LOG_RING = 120
    const val EVENT_RING = 60

    /** Safety valve: after this many consecutive unclean boots the hooks stay passive. */
    const val BOOT_GUARD_LIMIT = 3

    /** How long a boot must survive with hooks installed before the guard counter is cleared. */
    const val STABLE_WINDOW_MS = 90_000L
}
