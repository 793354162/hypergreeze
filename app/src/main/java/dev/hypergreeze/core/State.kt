package dev.hypergreeze.core

/**
 * Mirror of `com.miui.server.greeze.*` `FreezeState` without importing it: the module must stay
 * loadable on ROMs where the enum lives in a different class loader or has a different name.
 * Ordinals are the AIDL contract values (UNFROZEN=0, PARTIALLY_FROZEN=1, FULLY_FROZEN=2).
 */
enum class FreezeState(val label: String) {
    UNFROZEN("未冻结"),
    PARTIALLY_FROZEN("部分冻结"),
    FULLY_FROZEN("完全冻结"),
    UNKNOWN("未知");

    companion object {
        fun fromOrdinal(value: Int): FreezeState = when (value) {
            0 -> UNFROZEN
            1 -> PARTIALLY_FROZEN
            2 -> FULLY_FROZEN
            else -> UNKNOWN
        }

        fun fromName(value: String?): FreezeState =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Event kinds recorded by the hook side into the ring buffer. */
object EventType {
    const val FREEZE = "freeze"
    const val THAW = "thaw"
    const val SKIP = "skip"
    const val BLOCK = "block"
    const val ERROR = "error"
    const val INFO = "info"
    const val BOOT = "boot"
}

/**
 * One row of the event log. Kept deliberately flat and small: the whole ring buffer is rewritten
 * into a single `Settings.System` value, so every byte costs a provider write.
 */
data class EventRecord(
    val ts: Long,
    val type: String,
    val pkg: String?,
    val uid: Int,
    val reason: Int = 0,
    val result: String? = null,
    val detail: String? = null,
) {
    fun short(): String = buildString {
        append(type)
        if (uid != 0) append(" uid=").append(uid)
        if (!pkg.isNullOrEmpty()) append(' ').append(pkg)
        if (reason != 0) append(" reason=").append(reason)
        if (!result.isNullOrEmpty()) append(" -> ").append(result)
        if (!detail.isNullOrEmpty()) append(" (").append(detail).append(')')
    }
}

/** Hook installation state surfaced on the diagnostics page. */
object HookState {
    const val INSTALLED = "installed"
    const val MISSING = "missing"
    const val DISABLED = "disabled"
    const val ERROR = "error"
    const val SKIPPED = "skipped"
}

data class HookStatus(
    val id: String,
    val target: String,
    val state: String,
    val hits: Long = 0L,
    val errors: Long = 0L,
    val note: String? = null,
)

/**
 * Status snapshot the hook side publishes so the UI can show something truthful about a process
 * it cannot reach directly.
 */
data class Heartbeat(
    val ts: Long = 0L,
    val moduleVersion: String = "",
    val apiVersion: Int = 0,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val frameworkProperties: Long = 0L,
    val processName: String = "",
    val buildFingerprint: String = "",
    val greezePresent: Boolean = false,
    val greezeClass: String? = null,
    val instanceOk: Boolean = false,
    val policyEnabled: Boolean = false,
    val safeMode: Boolean = false,
    val safeModeReason: String? = null,
    val bootGuard: Int = 0,
    val revision: Long = 0L,
    val hooks: List<HookStatus> = emptyList(),
    val capabilities: Map<String, Boolean> = emptyMap(),
) {
    val installedHooks: Int get() = hooks.count { it.state == HookState.INSTALLED }
    val totalHooks: Int get() = hooks.size
}

/** A `RunningProcess` projection: enough for the sub-process editor. */
data class ProcessRow(
    val pid: Int,
    val uid: Int,
    val name: String,
    val frozen: Boolean? = null,
)
