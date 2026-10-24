package dev.hypergreeze.core

/**
 * Device-wide switches. Everything here is a *module* switch; GreeZe's own system properties
 * (`persist.sys.gz.enable`, `persist.sys.greeze.freezebinder`) are deliberately never written.
 */
data class GlobalPolicy(
    /** Master switch. When false every hook is a pass-through. */
    val enabled: Boolean = true,
    /** Force-freeze every app with mode IMMEDIATE as soon as it leaves the foreground. */
    val forceFreezeOnBackground: Boolean = true,
    /** Record freeze/thaw results into the event ring buffer. */
    val audit: Boolean = true,
    /**
     * Also push the "never" set into GreeZe's own list fields (`mNoRestrictAppSet` …).
     * Off by default: hooking the consumption point is cloud-proof, patching fields is not.
     */
    val patchLists: Boolean = false,
    /** Default delay applied when an app has no explicit `delayMs`. */
    val defaultDelayMs: Long = 0L,
    /** Mirror the module policy into GreeZe's cloud switches where a safe API exists. */
    val applyFreezeBinder: Boolean? = null,
    /** Keep the event ring buffer shorter to save Settings writes. */
    val compactEvents: Boolean = false,
) {
    companion object {
        val SAFE_DEFAULT = GlobalPolicy()
    }
}
