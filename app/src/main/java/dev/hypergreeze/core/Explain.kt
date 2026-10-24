package dev.hypergreeze.core

/**
 * How one [SceneBit] is currently treated: unset, forced exempt, or exemption dropped.
 */
enum class BitRole { NONE, KEEP, DROP }

/**
 * Turns a [Policy] into Chinese sentences so the UI only has to render strings.
 *
 * Three rules, because the whole point of this file is comprehension rather than documentation:
 *
 *  - describe the consequence, never the parameter: "即使命中了白名单也照冻", not
 *    "maskDrop = 64";
 *  - one short sentence per line, no exclamation marks, no selling language;
 *  - pure: no Android types, no clock, no locale, same input always gives the same output.
 *
 * [filterPreview] is a hand-copy of `PolicyEngine.filterVerdict` and must stay in lockstep with
 * it: NEVER always refuses; FOLLOW, an unconfigured package and a closed master switch all return
 * the system's own answer; `keep` wins over `drop` for the same reason; `filterPolarityInverted`
 * flips the module's own override and nothing else.
 */
object Explain {

    private const val MAX_APP_LINES = 6

    /** Union of every known [SceneBit.value]; any other bit in a mask is reported as unknown. */
    private val KNOWN_MASK: Int = SceneBit.entries.fold(0) { acc, bit -> acc or bit.value }

    // ---------------------------------------------------------------- modes

    /** 四态的一句话后果，用于模式选择器下方。 */
    fun modeLine(mode: FreezeMode): String = when (mode) {
        FreezeMode.FOLLOW -> "跟随系统：模块不介入，GreeZe 怎么判就怎么走"
        FreezeMode.NEVER -> "永不冻结：拒绝所有冻结请求，白名单判定一并拦下"
        FreezeMode.IMMEDIATE -> "立即冻结：退到后台就下发冻结，回前台自动解冻"
        FreezeMode.CUSTOM -> "自定义：只执行你显式打开的位，不继承任何默认"
    }

    // ----------------------------------------------------------- per app view

    /** 详情页顶部「这个应用现在会怎样」：3~6 行短句，按重要性排序。 */
    fun appLines(pkg: String, app: AppPolicy, global: GlobalPolicy): List<String> {
        val lines = ArrayList<String>(MAX_APP_LINES + 2)
        if (!global.enabled) lines.add("总开关已关闭，下面这些设置暂时都不生效")

        when (app.mode) {
            FreezeMode.FOLLOW -> {
                lines.add("跟随系统：GreeZe 自己判，模块不改它的结果")
                return lines.toList()
            }

            FreezeMode.NEVER -> lines.add("不会冻结：冻结请求一律拒绝，白名单判定也拦下")
            FreezeMode.IMMEDIATE -> lines.add("会冻结：退到后台开始计时，到点由模块主动冻结")
            FreezeMode.CUSTOM -> lines.add("会冻结：只认下面打开的位与开关，不继承默认")
        }

        val delay = if (app.delayMs > 0L) app.delayMs else global.defaultDelayMs
        val cooldown = app.thawCooldownMs
        if (app.mode == FreezeMode.IMMEDIATE) {
            val timing = StringBuilder()
            timing.append(if (delay > 0L) "退到后台 ${duration(delay)} 后冻结" else "退到后台立刻冻结")
            if (cooldown > 0L) timing.append("，解冻后 ${duration(cooldown)} 内不再冻")
            lines.add(timing.toString())
        } else if (app.mode == FreezeMode.CUSTOM && (delay > 0L || cooldown > 0L)) {
            val timing = StringBuilder()
            if (delay > 0L) timing.append("退到后台 ${duration(delay)} 后才判定")
            if (cooldown > 0L) {
                if (timing.isNotEmpty()) timing.append("，")
                timing.append("解冻后 ${duration(cooldown)} 内不再判定")
            }
            lines.add(timing.toString())
        }

        if (app.mode == FreezeMode.NEVER) {
            app.thawOn.externalWake?.let { allowed ->
                lines.add(
                    if (allowed) "允许被外部唤醒，它本来也不会被冻"
                    else "不允许被外部唤醒，只能你自己打开它",
                )
            }
            return cap(lines)
        }

        // Exceptions: the two masks, then sub-processes.
        val keep = app.keepBits
        val drop = app.dropBits
        if (keep.isNotEmpty() || drop.isNotEmpty()) {
            val text = StringBuilder()
            if (keep.isNotEmpty()) text.append("命中${labels(keep)}时不冻它")
            if (keep.isNotEmpty() && drop.isNotEmpty()) text.append("，")
            if (drop.isNotEmpty()) text.append("命中${labels(drop)}时照冻")
            lines.add(text.toString())
        }

        val locked = drop.intersect(SceneBit.LOCKED_DROP)
        if (locked.isNotEmpty()) {
            lines.add("drop 里含${labels(locked)}，这类位不允许丢，前台用着也会被冻")
        }

        val overrides = app.processes.filterValues { it != FreezeMode.FOLLOW }
        if (overrides.isNotEmpty()) {
            val detail = overrides.entries.joinToString("、") {
                "${processShortName(pkg, it.key)}=${it.value.label}"
            }
            lines.add(
                if (detail.length <= 28) "子进程单独设置：$detail"
                else "${overrides.size} 个子进程有单独设置，展开可看",
            )
        }

        // Resource follow-ups and thaw conditions.
        val resOn = ResourceSwitches.ORDER.map { it.first }.filter { resourceValue(app.resources, it) == true }
        val resOff = ResourceSwitches.ORDER.map { it.first }.filter { resourceValue(app.resources, it) == false }
        if (resOn.isNotEmpty()) {
            lines.add("冻结时额外限制：${resOn.joinToString("、") { resourceNoun(it) }}")
        } else if (resOff.isNotEmpty()) {
            lines.add("只冻进程，不动${resOff.joinToString("、") { resourceNoun(it) }}")
        }

        val thawOn = ThawTriggers.ORDER.map { it.first }.filter { thawValue(app.thawOn, it) == true }
        val thawOff = ThawTriggers.ORDER.map { it.first }.filter { thawValue(app.thawOn, it) == false }
        if (thawOn.isNotEmpty()) {
            lines.add("${thawOn.joinToString("、") { thawNoun(it) }}会解冻它")
        } else if (thawOff.isNotEmpty()) {
            lines.add("${thawOff.joinToString("、") { thawNoun(it) }}也不解冻")
        }

        return cap(lines)
    }

    // ------------------------------------------------------------- home view

    /** 首页摘要，一句话：例如「3 个应用永不冻结 · 2 个退后台立刻冻 · 其余跟随系统」。 */
    fun summary(policy: Policy): String {
        val never = policy.apps.count { it.value.mode == FreezeMode.NEVER }
        val immediate = policy.apps.count { it.value.mode == FreezeMode.IMMEDIATE }
        val custom = policy.apps.count { it.value.mode == FreezeMode.CUSTOM }

        if (never + immediate + custom == 0) {
            val head = if (policy.global.enabled) "" else "总开关已关闭，"
            val tail = if (policy.apps.isEmpty()) {
                ""
            } else {
                "（另有 ${policy.apps.size} 条记录仍跟随系统）"
            }
            return "${head}当前没有任何应用被改动，模块对系统无影响$tail"
        }

        val parts = ArrayList<String>(4)
        if (never > 0) parts.add("$never 个应用永不冻结")
        if (immediate > 0) parts.add("$immediate 个退后台立刻冻")
        if (custom > 0) parts.add("$custom 个按自定义规则冻")
        parts.add("其余跟随系统")
        val core = parts.joinToString(" · ")
        return if (policy.global.enabled) core else "总开关已关闭 · $core"
    }

    // ------------------------------------------------------------- bit helpers

    /** reason 位掩码 → 人类可读，例如 "音频播放 + 白名单"；0 → "无原因位"。 */
    fun reasonLabel(reason: Int): String = describeMask(reason, " + ")

    /** 单个场景位在某个角色下的后果说明（UI 的「?」用它）。 */
    fun bitEffect(bit: SceneBit, role: BitRole): String = when (role) {
        BitRole.NONE -> "没设置：${bit.label} 由系统自己判断，模块不表态"
        BitRole.KEEP -> if (bit in SceneBit.LOCKED_DROP) {
            "保持：${bit.label} 期间系统不会冻它，前台用着安全"
        } else {
            "保持：命中 ${bit.label} 时模块不放行冻结"
        }
        BitRole.DROP -> if (bit in SceneBit.LOCKED_DROP) {
            "不允许 drop：丢了这一位，${bit.label} 时前台用着也会被冻"
        } else {
            "丢弃：即使命中 ${bit.label}，模块也照样放行冻结"
        }
    }

    // ------------------------------------------------------------- simulation

    /**
     * 策略试算：给定包名与 reason 位、以及「系统原本的答案」，说明模块会怎么判。
     *
     * Mirrors `PolicyEngine.filterVerdict` case by case; see the type doc for the precedence.
     */
    fun filterPreview(policy: Policy, pkg: String, reason: Int, original: Boolean): String {
        val kept = "系统原值 ${yesNo(original)} 保持"
        if (!policy.global.enabled) return "跟随系统：总开关关闭，$kept"
        val app = policy.app(pkg) ?: return "跟随系统：该应用未配置，$kept"
        when (app.mode) {
            FreezeMode.FOLLOW -> return "跟随系统：这个应用设成了跟随系统，$kept"
            FreezeMode.NEVER -> return "不会冻结：永不冻结会拒绝所有冻结请求"
            FreezeMode.IMMEDIATE, FreezeMode.CUSTOM -> Unit
        }

        val keepHit = app.maskKeep and reason
        val dropHit = app.maskDrop and reason
        val override: Boolean? = when {
            keepHit != 0 -> false
            dropHit != 0 -> true
            else -> null
        }
        val inverted = policy.global.filterPolarityInverted
        return when (override) {
            null -> "会不会冻结：没有命中任何掩码位，$kept"
            false -> if (!inverted) {
                "不会冻结：命中了 keep 位（${describeMask(keepHit, " + ")}）"
            } else {
                "会冻结：极性反了，keep 位不再放行（${describeMask(keepHit, " + ")}）"
            }
            true -> if (!inverted) {
                "会冻结：drop 位覆盖了系统豁免（${describeMask(dropHit, " + ")}）"
            } else {
                "不会冻结：极性反了，drop 位不再拦截（${describeMask(dropHit, " + ")}）"
            }
        }
    }

    // ------------------------------------------------------ resource / thaw rows

    /** 资源开关的后果说明，key 取 "net"/"alarm"/"job"/"wakelock"/"broadcast"/"cpu"。 */
    fun resourceLine(key: String, value: Boolean?): String {
        val noun = resourceNoun(key)
        if (value == null) return "跟随系统：$noun 怎么处理由 GreeZe 自己决定"
        return if (value) {
            when (key) {
                "net" -> "冻结后网络断了，解冻前收不到任何消息"
                "alarm" -> "冻结期间的闹钟被压后，到点任务会延后"
                "job" -> "后台同步卡住，解冻后一次性补跑"
                "wakelock" -> "拿不到唤醒锁，长连接会断"
                "broadcast" -> "后台广播被拦，静态接收器收不到"
                "cpu" -> "冻结时额外限频，回前台可能先卡一下"
                else -> "$noun 在冻结期间会被额外限制"
            }
        } else {
            when (key) {
                "net" -> "不额外切网，冻结只停进程本身"
                "alarm" -> "闹钟照常响，不会被冻结压后"
                "job" -> "后台任务照常排队，不额外拦"
                "wakelock" -> "不拦唤醒锁，系统仍能叫醒它"
                "broadcast" -> "后台广播照常送达"
                "cpu" -> "不做额外限频，调度不受影响"
                else -> "明确不额外限制 $noun"
            }
        }
    }

    /** 解冻条件的后果说明，key 取 "screenOn"/"netChange"/"notification"/"audioFocus"/"charging"/"externalWake"。 */
    fun thawLine(key: String, value: Boolean?): String {
        val noun = thawNoun(key)
        if (value == null) return "跟随系统：$noun 要不要解冻由 GreeZe 决定"
        return if (value) {
            when (key) {
                "screenOn" -> "亮屏就解冻，代价是亮屏时它可能耗电"
                "netChange" -> "网络一变就解冻，适合等消息的应用"
                "notification" -> "推送到达就解冻，通知能正常弹出"
                "audioFocus" -> "拿到音频焦点就解冻，后台播放不断"
                "charging" -> "插上电就解冻，后台任务这时能跑"
                "externalWake" -> "允许别的应用或系统把它叫醒"
                else -> "$noun 时会解冻"
            }
        } else {
            when (key) {
                "screenOn" -> "亮屏不解冻，熄屏后状态保持不变"
                "netChange" -> "换个网络也不解冻，WiFi 与流量切换一样"
                "notification" -> "收到推送也不解冻，打开应用才看到"
                "audioFocus" -> "抢到音频焦点也不解冻，后台播放会断"
                "charging" -> "插电也不解冻，充电时照样冻着"
                "externalWake" -> "不允许被外部叫醒，只能你自己打开"
                else -> "$noun 时也不会解冻"
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun cap(lines: MutableList<String>): List<String> {
        if (lines.size <= MAX_APP_LINES) return lines.toList()
        val head = ArrayList<String>(MAX_APP_LINES)
        for (index in 0 until MAX_APP_LINES - 1) head.add(lines[index])
        head.add("其余 ${lines.size - head.size} 项设置展开可看")
        return head
    }

    private fun describeMask(mask: Int, separator: String): String {
        if (mask == 0) return "无原因位"
        val parts = SceneBit.fromMask(mask).mapTo(ArrayList<String>(4)) { it.label }
        val unknown = mask and KNOWN_MASK.inv()
        if (unknown != 0) parts.add("未知位 0x" + unknown.toString(16))
        return parts.joinToString(separator)
    }

    private fun labels(bits: Set<SceneBit>): String = bits.joinToString("、") { it.label }

    private fun yesNo(value: Boolean): String = if (value) "true" else "false"

    private fun duration(ms: Long): String {
        if (ms <= 0L) return "0"
        if (ms < 1000L) return "$ms 毫秒"
        val seconds = ms / 1000L
        val tenths = (ms % 1000L) / 100L
        return if (tenths == 0L) "$seconds 秒" else "$seconds.$tenths 秒"
    }

    private fun processShortName(pkg: String, full: String): String {
        val prefix = "$pkg:"
        return if (full.startsWith(prefix)) full.removePrefix(prefix) else full
    }

    private fun resourceValue(res: ResourceSwitches, key: String): Boolean? = when (key) {
        "net" -> res.net
        "alarm" -> res.alarm
        "job" -> res.job
        "wakelock" -> res.wakelock
        "broadcast" -> res.broadcast
        "cpu" -> res.cpu
        else -> null
    }

    private fun thawValue(thaw: ThawTriggers, key: String): Boolean? = when (key) {
        "screenOn" -> thaw.screenOn
        "netChange" -> thaw.netChange
        "notification" -> thaw.notification
        "audioFocus" -> thaw.audioFocus
        "charging" -> thaw.charging
        "externalWake" -> thaw.externalWake
        else -> null
    }

    private fun resourceNoun(key: String): String = when (key) {
        "net" -> "网络"
        "alarm" -> "闹钟"
        "job" -> "后台任务"
        "wakelock" -> "唤醒锁"
        "broadcast" -> "后台广播"
        "cpu" -> "CPU 频率"
        else -> key
    }

    private fun thawNoun(key: String): String = when (key) {
        "screenOn" -> "亮屏"
        "netChange" -> "网络变化"
        "notification" -> "推送"
        "audioFocus" -> "音频焦点"
        "charging" -> "充电"
        "externalWake" -> "外部唤醒"
        else -> key
    }
}
