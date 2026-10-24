package dev.hypergreeze.core

/**
 * One app's policy. [mode] decides which of the other fields are consulted:
 *
 *  - [FreezeMode.FOLLOW]    -> every field below is ignored
 *  - [FreezeMode.NEVER]     -> [thawOn] is still honoured (it only widens what is allowed)
 *  - [FreezeMode.IMMEDIATE] -> [delayMs], [thawCooldownMs], [maskDrop], [thawOn] are used
 *  - [FreezeMode.CUSTOM]    -> everything the user set is used, nothing is inherited
 *
 * `maskKeep` / `maskDrop` are raw bitmasks of [SceneBit.value]; the codec stores them as ints so
 * that a hand-edited JSON (via `adb shell settings put`) stays valid.
 */
data class AppPolicy(
    val mode: FreezeMode = FreezeMode.FOLLOW,
    /** Delay between "app went to background" and our forced freeze. */
    val delayMs: Long = 0L,
    /** Do not force-freeze again if the app was thawed less than this long ago (anti-jitter). */
    val thawCooldownMs: Long = 0L,
    /** Bits forced to stay exempt (never frozen because of this reason). */
    val maskKeep: Int = 0,
    /** Bits whose exemption is dropped (freeze even though this reason is active). */
    val maskDrop: Int = 0,
    /** Optional per-process override, keyed by the full process name (`pkg:sub`). */
    val processes: Map<String, FreezeMode> = emptyMap(),
    val resources: ResourceSwitches = ResourceSwitches(),
    val thawOn: ThawTriggers = ThawTriggers(),
    val note: String = "",
) {
    val keepBits: Set<SceneBit> get() = SceneBit.fromMask(maskKeep)
    val dropBits: Set<SceneBit> get() = SceneBit.fromMask(maskDrop)

    /** True when this entry would actually change behaviour. */
    val isEffective: Boolean
        get() = mode != FreezeMode.FOLLOW ||
            maskKeep != 0 ||
            maskDrop != 0 ||
            processes.values.any { it != FreezeMode.FOLLOW }

    fun processMode(processName: String?): FreezeMode? =
        if (processName == null) null else processes[processName]

    fun withMask(keep: Set<SceneBit>, drop: Set<SceneBit>): AppPolicy =
        copy(maskKeep = SceneBit.toMask(keep), maskDrop = SceneBit.toMask(drop - SceneBit.LOCKED_DROP))
}
