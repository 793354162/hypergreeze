package dev.hypergreeze.core

/**
 * Resource follow-ups that GreeZe may or may not apply when an app is frozen (L6 in the report).
 *
 * Every switch is tri-state: `null` = leave whatever GreeZe decided alone. The hook side only
 * *adds* restrictions the user explicitly asked for; it never relaxes a system-side restriction,
 * because doing so requires calling setters that are far more invasive than the freeze path.
 */
data class ResourceSwitches(
    val net: Boolean? = null,
    val alarm: Boolean? = null,
    val job: Boolean? = null,
    val wakelock: Boolean? = null,
    val broadcast: Boolean? = null,
    val cpu: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = net == null && alarm == null && job == null && wakelock == null &&
            broadcast == null && cpu == null

    /** Labels for the detail page, in display order. */
    companion object {
        val ORDER: List<Pair<String, String>> = listOf(
            "net" to "切断网络",
            "alarm" to "缓存闹钟",
            "job" to "限制 JobScheduler",
            "wakelock" to "限制 Wakelock",
            "broadcast" to "拦截后台广播",
            "cpu" to "CPU 限频",
        )
    }
}

/**
 * Events that must thaw an app even when the policy says it stays frozen (L7).
 */
data class ThawTriggers(
    val screenOn: Boolean? = null,
    val netChange: Boolean? = null,
    val notification: Boolean? = null,
    val audioFocus: Boolean? = null,
    val charging: Boolean? = null,
    val externalWake: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = screenOn == null && netChange == null && notification == null &&
            audioFocus == null && charging == null && externalWake == null

    companion object {
        val ORDER: List<Pair<String, String>> = listOf(
            "screenOn" to "亮屏解冻",
            "netChange" to "网络变化解冻",
            "notification" to "推送解冻",
            "audioFocus" to "音频焦点解冻",
            "charging" to "充电时解冻",
            "externalWake" to "允许被外部唤醒",
        )
    }
}
