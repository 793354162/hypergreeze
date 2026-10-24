package dev.hypergreeze.core

/**
 * Per-app intents. `FOLLOW` is the safe default and means "the module does not touch this app";
 * every other value is an explicit override of GreeZe's own decision.
 */
enum class FreezeMode(val wire: String, val label: String, val description: String) {
    /** Nothing is intercepted for this app; GreeZe behaves exactly as stock. */
    FOLLOW("follow", "跟随系统", "完全交给 GreeZe 决策，模块不做任何干预"),

    /** All freeze paths are refused for this app. */
    NEVER("never", "永不冻结", "拦截白名单判定并拒绝所有冻结请求"),

    /** Freeze as soon as the app leaves the foreground (after [AppPolicy.delayMs]). */
    IMMEDIATE("immediate", "立即冻结", "一退到后台就主动下发冻结请求"),

    /** Inheritance-free mode: only the explicit switches below apply. */
    CUSTOM("custom", "自定义", "只执行你显式打开的掩码/子进程/资源/解冻规则");

    val isOverride: Boolean get() = this != FOLLOW

    companion object {
        fun fromWire(value: String?): FreezeMode =
            entries.firstOrNull { it.wire == value } ?: FOLLOW

        val ordered: List<FreezeMode> = entries.toList()
    }
}
