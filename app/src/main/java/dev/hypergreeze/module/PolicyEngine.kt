package dev.hypergreeze.module

import dev.hypergreeze.core.AppPolicy
import dev.hypergreeze.core.FreezeMode
import dev.hypergreeze.core.Policy
import dev.hypergreeze.core.SceneBit

/**
 * Pure decision layer: `Policy` + (package, process, reason) -> answer. No Android types, no
 * reflection, no side effects, so every rule below can be reasoned about (and unit-tested) in
 * isolation from GreeZe.
 *
 * Design rule: **FOLLOW means untouched.** If an app is not configured, every helper here returns
 * the caller's `original` value and the module is invisible to that app.
 */
class PolicyEngine(val policy: Policy) {

    val enabled: Boolean get() = policy.global.enabled

    fun appPolicy(pkg: String?): AppPolicy? = pkg?.let { policy.apps[it] }

    fun mode(pkg: String?): FreezeMode = appPolicy(pkg)?.mode ?: FreezeMode.FOLLOW

    /** Process override wins; a process entry without an override inherits the app mode. */
    fun mode(pkg: String?, processName: String?): FreezeMode {
        val app = appPolicy(pkg) ?: return FreezeMode.FOLLOW
        return app.processMode(processName) ?: app.mode
    }

    fun neverFreeze(pkg: String?, processName: String? = null): Boolean =
        enabled && mode(pkg, processName) == FreezeMode.NEVER

    /** True when the module itself should push a freeze as soon as the app goes background. */
    fun forceFreezeOnBackground(pkg: String?, processName: String? = null): Boolean =
        enabled &&
            policy.global.forceFreezeOnBackground &&
            mode(pkg, processName) == FreezeMode.IMMEDIATE

    fun delayMs(pkg: String?): Long {
        val explicit = appPolicy(pkg)?.delayMs ?: 0L
        return if (explicit > 0L) explicit else policy.global.defaultDelayMs
    }

    fun cooldownMs(pkg: String?): Long = appPolicy(pkg)?.thawCooldownMs ?: 0L

    fun keepsBit(pkg: String?, bit: SceneBit): Boolean =
        (appPolicy(pkg)?.maskKeep ?: 0) and bit.value != 0

    fun dropsBit(pkg: String?, bit: SceneBit): Boolean =
        (appPolicy(pkg)?.maskDrop ?: 0) and bit.value != 0

    /**
     * The single most important decision: what `AurogonFilterManager.filter(uid, pkg, reason)`
     * should return. `true` means "freezing is allowed" (report §2.3).
     *
     *  - app in NEVER            -> false (never allow a freeze)
     *  - reason bit in `keep`    -> false (force the exemption)
     *  - reason bit in `drop`    -> true  (ignore GreeZe's exemption)
     *  - otherwise               -> `original`, i.e. stock behaviour
     */
    fun filterVerdict(
        pkg: String?,
        reason: Int,
        original: Boolean,
        processName: String? = null,
    ): Boolean {
        if (!enabled || pkg == null) return original
        // Single map lookup: this runs on every GreeZe filter() call.
        val app = appPolicy(pkg) ?: return original
        val effective = app.processMode(processName) ?: app.mode
        return when (effective) {
            FreezeMode.FOLLOW -> original
            FreezeMode.NEVER -> false
            FreezeMode.IMMEDIATE, FreezeMode.CUSTOM -> {
                val override = when {
                    app.maskKeep and reason != 0 -> false
                    app.maskDrop and reason != 0 -> true
                    else -> null
                }
                when {
                    override == null -> original
                    policy.global.filterPolarityInverted -> !override
                    else -> override
                }
            }
        }
    }

    /** `isNoRestrictApp` / `isTempWhite` answer. */
    fun whitelistVerdict(pkg: String?, original: Boolean): Boolean {
        if (!enabled || pkg == null) return original
        val app = appPolicy(pkg) ?: return original
        return when (app.mode) {
            FreezeMode.NEVER -> true
            FreezeMode.IMMEDIATE -> if (dropsBit(pkg, SceneBit.NO_RESTRICT)) false else original
            FreezeMode.CUSTOM -> if (dropsBit(pkg, SceneBit.NO_RESTRICT)) false else original
            FreezeMode.FOLLOW -> original
        }
    }

    /** `isAllowWakeUpList` answer: may this app be woken by somebody else? */
    fun wakeUpVerdict(pkg: String?, original: Boolean): Boolean {
        if (!enabled || pkg == null) return original
        val app = appPolicy(pkg) ?: return original
        if (app.mode == FreezeMode.FOLLOW) return original
        return app.thawOn.externalWake ?: original
    }

    fun resource(key: String, pkg: String?): Boolean? {
        val res = appPolicy(pkg)?.resources ?: return null
        return when (key) {
            "net" -> res.net
            "alarm" -> res.alarm
            "job" -> res.job
            "wakelock" -> res.wakelock
            "broadcast" -> res.broadcast
            "cpu" -> res.cpu
            else -> null
        }
    }

    fun thawTrigger(key: String, pkg: String?): Boolean? {
        val thaw = appPolicy(pkg)?.thawOn ?: return null
        return when (key) {
            "screenOn" -> thaw.screenOn
            "netChange" -> thaw.netChange
            "notification" -> thaw.notification
            "audioFocus" -> thaw.audioFocus
            "charging" -> thaw.charging
            "externalWake" -> thaw.externalWake
            else -> null
        }
    }

    /** One-line human summary for the diagnostics page. */
    fun summary(): String = buildString {
        append("enabled=").append(policy.global.enabled)
        append(" rev=").append(policy.revision)
        append(" apps=").append(policy.apps.size)
        append(" immediate=").append(policy.apps.count { it.value.mode == FreezeMode.IMMEDIATE })
        append(" never=").append(policy.apps.count { it.value.mode == FreezeMode.NEVER })
    }
}
