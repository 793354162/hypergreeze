package dev.hypergreeze.module

import dev.hypergreeze.core.Module

/**
 * Boot-loop guard.
 *
 * A module that hooks `system_server` is one bad assumption away from an unbootable phone, so the
 * guard is deliberately dumb and un-hookable: a `persist.sys.*` property counter that is bumped
 * before any hook is installed and cleared 90 s after a successful boot. Three consecutive boots
 * that never reach the stable window put the module into passive mode — hooks are still installed
 * (so the diagnostics page can explain itself) but every decision returns the stock value.
 *
 * Properties are used instead of `Settings.System` because the guard must be readable before a
 * `Context` exists, and because nothing in userspace can reset it accidentally.
 */
class SafetyGuard {

    data class Decision(
        val passive: Boolean,
        val reason: String?,
        val bootGuard: Int,
        val propertyAvailable: Boolean,
    )

    @Volatile
    var decision: Decision? = null
        private set

    @Volatile
    var passive: Boolean = false
        private set

    fun begin(): Decision {
        val available = SysProp.available
        val previous = SysProp.getInt(PROP_GUARD, 0)
        val next = previous + 1
        if (available) SysProp.setInt(PROP_GUARD, next)

        val manual = available && SysProp.getInt(PROP_SAFE, 0) == 1
        val overLimit = available && next >= Module.BOOT_GUARD_LIMIT
        val isPassive = manual || overLimit
        passive = isPassive

        val reason = when {
            manual -> "手动请求安全模式"
            overLimit -> "连续 $next 次启动未进入稳定窗口"
            else -> null
        }
        if (isPassive) {
            SysProp.set(PROP_SAFE_REASON, reason.orEmpty())
            Logx.w("SAFE MODE: $reason")
        } else {
            Logx.i("boot guard $previous -> $next (limit ${Module.BOOT_GUARD_LIMIT})")
        }
        val result = Decision(isPassive, reason, next, available)
        decision = result
        return result
    }

    /** Called after the stable window elapsed with hooks installed and no fatal error. */
    fun markStable() {
        if (passive) return
        SysProp.setInt(PROP_GUARD, 0)
        SysProp.set(PROP_SAFE_REASON, "")
        Logx.i("stable window reached; boot guard cleared")
    }

    /** UI-driven reset: clears both the safe-mode latch and the guard counter. */
    fun clearSafeMode() {
        SysProp.setInt(PROP_SAFE, 0)
        SysProp.setInt(PROP_GUARD, 0)
        SysProp.set(PROP_SAFE_REASON, "")
        passive = false
        Logx.i("safe mode cleared by request")
    }

    fun safeModeReason(): String? = SysProp.get(PROP_SAFE_REASON).ifEmpty { null }

    companion object {
        const val PROP_GUARD = "persist.sys.hypergreeze.guard"
        const val PROP_SAFE = "persist.sys.hypergreeze.safe"
        const val PROP_SAFE_REASON = "persist.sys.hypergreeze.safe_reason"
    }
}
