package dev.hypergreeze.core

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * 副作用账本（side-effect ledger）里的一类改动。
 *
 * 这个模块调用 GreeZe 的内部方法时，每一次都会改变用户机器的真实状态：冻结/解冻、限制网络
 * 与 Job/Wakelock、改写名单、切 binder 开关、触发一次模式。用户的要求是「模块所做的一切改变
 * 都可还原」，所以每次改动都要落一行账，能按账本反做。
 *
 * `wire` 是落进 [JournalCodec] 文档的稳定短名，**跨版本不许改**（老文档要能被新代码解开）；
 * `label` 只给人看，可以随时换措辞；`reversible` 表示这一类 op 语义上有没有反做动作。
 *
 * 反做表（接还原逻辑时按 op 走，不要按 label 走）：
 * - [FREEZE] → 反做是解冻；[THAW] 本身 `reversible=false`，因为解冻之后想冻回去得知道原来的
 *   冻结方式（delay/mask），那不是账本的一行能表达的。
 * - [NET_RESTRICT]、[WAKELOCK_LIMIT]、[FREEZE_BINDER]、[LIST_PATCH] → 用 [JournalEntry.before]
 *   写回；这几项按设计才标记为可还原。
 * - [JOB_RESTRICT]、[QUICK_MODE]、[REVERT] 按设计不提供反做：前者没有可靠的回读旧值，后两者
 *   是一次性触发，触发本身不是状态。
 *
 * 注意 `reversible` 只说「存在反做动作」，不说「这次调用成功了」。真正能撤的集合 =
 * `reversible && ok`，见 [JournalRing.undoableCount]。
 */
enum class EffectOp(val wire: String, val label: String, val reversible: Boolean) {
    FREEZE("freeze", "冻结", true),
    THAW("thaw", "解冻", false),
    NET_RESTRICT("net", "网络限制", true),
    JOB_RESTRICT("job", "Job 限制", false),
    WAKELOCK_LIMIT("wakelock", "Wakelock 限制", true),
    FREEZE_BINDER("freezeBinder", "Binder 冻结开关", true),
    LIST_PATCH("listPatch", "写入名单", true),
    QUICK_MODE("quickMode", "触发模式", false),
    REVERT("revert", "执行还原", false),
    ;

    companion object {
        /**
         * 线上值 -> 枚举。精确匹配：大小写不对、多了空格、或者是我们没登记过的 op，一律返回
         * null，让解码器把这一条当脏数据丢掉，而不是猜一个「最接近」的 op 去执行。
         */
        fun fromWire(value: String?): EffectOp? {
            if (value == null) return null
            for (op in entries) if (op.wire == value) return op
            return null
        }
    }
}

/**
 * 账本的一行 = 模块对机器做过的一次改动。
 *
 * 字段的取舍是为还原服务的，不是为了好看：
 * - [before] 能读到旧值时必须填（`"false"`、`"size=3"`、`"null"` 都算值）；只有真的读不到才留
 *   null。留 null 的那条在还原时意味着「先探测再写」，UI 要显式提示，不要假装能一键还原。
 * - [after] 是我们设成的值，不是「期望值」——失败的那次也要填，方便对照现场。
 * - [ok] 记录这次调用本身有没有抛/返回失败；失败的行不参与还原，但**必须留在账本里**，否则
 *   「我明明点过」这件事就没有证据了。
 * - [revision] 是当时的策略版本号：还原时如果 revision 已经变了，说明用户之后又改过策略，
 *   按旧行硬写会把新策略踩掉。
 */
data class JournalEntry(
    val seq: Long = 0L,
    val ts: Long = 0L,
    val op: EffectOp = EffectOp.FREEZE,
    /** 人读的目标描述，如 `"com.tencent.mm (u10123)"` / `"mNoRestrictAppSet"`。 */
    val target: String = "",
    val uid: Int = 0,
    val pkg: String? = null,
    /** 能取到旧值时填，取不到留 null。 */
    val before: String? = null,
    /** 我们设成的值，如 `"true"` / `"false"` / `"size=3"`。 */
    val after: String? = null,
    /** 这次调用是否成功。 */
    val ok: Boolean = true,
    /** 当时的策略 revision。 */
    val revision: Long = 0L,
    val note: String? = null,
) {
    /**
     * 一行摘要，给日志页/Toast 用：`12:03:44 网络限制 com.x (u10123) true→false ok`。
     *
     * 时间按本地时区取 `HH:mm:ss`；`ts` 还是 0（还没经过 [JournalRing.add]）时显示 `--:--:--`，
     * 好让「没打时间戳」这件事一眼可见，而不是伪装成 08:00:00。
     *
     * `uid` 只在 [target] 里还没写过同一个 uid 时才补：调用方有的地方把 uid 塞进 target
     * （`"com.x (u10123)"`）、有的地方只填 [uid] 字段，两种都用时不能出现 `(u10123) (u10123)`。
     */
    val oneLine: String
        get() = buildString {
            append(clock(ts))
            append(' ').append(op.label)
            if (target.isNotBlank()) append(' ').append(target)
            if (uid != 0 && !target.endsWith("(u$uid)")) append(" (u").append(uid).append(')')
            if (after != null) append(' ').append(before ?: "?").append('→').append(after)
            append(if (ok) " ok" else " fail")
            if (!note.isNullOrBlank()) append(" — ").append(note)
        }
}

/** `HH:mm:ss`（本地时区）。手写而不用 `SimpleDateFormat`：UI 一屏几百行，不想每行 new 一个 formatter。 */
private fun clock(ts: Long): String {
    if (ts <= 0L) return "--:--:--"
    val cal = Calendar.getInstance()
    cal.timeInMillis = ts
    return buildString(8) {
        append(pad2(cal.get(Calendar.HOUR_OF_DAY)))
        append(':').append(pad2(cal.get(Calendar.MINUTE)))
        append(':').append(pad2(cal.get(Calendar.SECOND)))
    }
}

private fun pad2(value: Int): String = if (value < 0) "00" else if (value < 10) "0$value" else value.toString()

/** 「这一行现在能不能反做」的唯一判据，别在调用点各写一遍。 */
private val JournalEntry.undoable: Boolean get() = ok && op.reversible

/**
 * [JournalEntry] <-> JSON。格式故意短：整份文档要写进 `Settings.System`，每个字节都算一次
 * provider 写，所以一个字段名都不肯写全。
 *
 * ```
 * [{"s":1,"ts":1699999999999,"op":"net","t":"com.x (u10123)","u":10123,"p":"com.x","b":"false","a":"true","ok":true,"r":7,"n":"..."}]
 * ```
 *
 * - 恒写 `op`（身份）、`s`、`ts`、`ok`：少任何一个，解出来就不是同一行账。
 * - 缺省省略：`t` 为空串、`u`/`r` 为 0、`p`/`b`/`a`/`n` 为 null 时都不写（[JSONObject.putOpt]
 *   的行为），省下来的字节是真金白银。
 * - 解码容错：缺 `op` / 未知 `op` / 元素不是对象 → 跳过该条；类型不对 → 回落默认值；整份非法
 *   → 空表。**任何输入都不抛**。
 * - 不认识的字段一律忽略，新旧版本可以共存于同一个 key。
 *
 * 注意 `org.json` 的 `JSONObject` 用的是 HashMap，**字段输出顺序不保证**（也不保证和我们写的
 * 顺序一致）。所以别拿字符串比对账本内容，要比就比解码后的结构。
 */
object JournalCodec {
    /** 一次解码超过这么多条就只留最后 [KEEP_ON_FLOOD] 条：一份被写坏的文档不该把进程拖死。 */
    private const val FLOOD = 1000

    private const val KEEP_ON_FLOOD = 200

    fun encode(entries: List<JournalEntry>): String {
        val array = JSONArray()
        for (entry in entries) array.put(encodeEntry(entry))
        return array.toString()
    }

    /**
     * 解码。这里**不做**容量截断（容量是 [JournalRing] 的事，一份 300 条的文档仍应解出 300 条），
     * 只有原始条数超过 [FLOOD] 时才保留最后 [KEEP_ON_FLOOD] 条。
     *
     * 说明：这条规则挡的是「把几千条塞进账本/UI」，不是解析期内存——`JSONArray(raw)` 已经先把
     * 整份文档建出来了。真要省解析内存，只能在上层限制写入长度，那是 [JournalRing] 容量的事。
     */
    fun decode(raw: String?): List<JournalEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val total = array.length()
            if (total == 0) return emptyList()
            val start = if (total > FLOOD) total - KEEP_ON_FLOOD else 0
            val out = ArrayList<JournalEntry>(total - start)
            for (i in start until total) {
                val item = array.optJSONObject(i) ?: continue
                val entry = decodeEntry(item) ?: continue
                out.add(entry)
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun encodeEntry(entry: JournalEntry): JSONObject = JSONObject()
        .put("s", entry.seq)
        .put("ts", entry.ts)
        .put("op", entry.op.wire)
        .putOpt("t", entry.target.takeIf { it.isNotBlank() })
        .putOpt("u", entry.uid.takeIf { it != 0 })
        .putOpt("p", entry.pkg)
        .putOpt("b", entry.before)
        .putOpt("a", entry.after)
        .put("ok", entry.ok)
        .putOpt("r", entry.revision.takeIf { it != 0L })
        .putOpt("n", entry.note)

    /** 返回 null 表示「这条读不出来，丢掉」——只有 op 读不懂时才给 null。 */
    private fun decodeEntry(item: JSONObject): JournalEntry? {
        val op = EffectOp.fromWire(str(item, "op")) ?: return null
        return JournalEntry(
            seq = item.oLong("s") ?: 0L,
            ts = item.oLong("ts") ?: 0L,
            op = op,
            target = str(item, "t").orEmpty(),
            uid = item.oInt("u") ?: 0,
            pkg = str(item, "p"),
            before = str(item, "b"),
            after = str(item, "a"),
            ok = okFlag(item),
            revision = item.oLong("r") ?: 0L,
            note = str(item, "n"),
        )
    }

    /**
     * 严格读字符串：只有 JSON string 才算值。数字/对象/数组都是类型错误，回落「没有这个字段」。
     * 这里不能用 [JSONObject.optString]，它会把 `7` 读成 `"7"`、把 `{}` 读成 `"{}"`，等于把脏
     * 数据当成账本内容。
     */
    private fun str(obj: JSONObject, key: String): String? {
        val value = obj.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        return value as? String
    }

    /**
     * `ok` 是唯一默认值为 `true` 的布尔字段，所以不能用 `oBool` 那条路：它对垃圾值返回的是
     * `false`，会把「读不懂」误记成「调用失败」。这里显式用两参版本，垃圾值回落到默认的 true。
     */
    private fun okFlag(obj: JSONObject): Boolean =
        if (obj.has("ok") && !obj.isNull("ok")) obj.optBoolean("ok", true) else true
}

/**
 * 固定容量的账本环。
 *
 * 容量是「UI 能往上翻多少行」，不是「能撤多少件事」；超容量就丢最旧的那条，并且不报警——
 * 账本丢一行旧账，比整个模块卡住好。
 *
 * ```kotlin
 * val ring = JournalRing(200)
 * ring.add(JournalEntry(op = EffectOp.NET_RESTRICT, target = "com.x (u10123)", after = "false"))
 * JournalCodec.encode(ring.snapshot())   // 落 Settings.System
 * ```
 *
 * 线程：hook 侧可能从多个 Binder 线程写、UI 线程同时读，所有方法都在同一把锁里做。
 * 容量 <= 0 一律当 1：环里总留得下「刚发生的那一条」，否则 [add] 的返回值没有意义。
 */
class JournalRing(val capacity: Int = 200) {

    private val limit: Int = capacity.coerceAtLeast(1)
    private val lock = Any()
    private val items = ArrayList<JournalEntry>(limit.coerceAtMost(256))
    private var nextSeq = 1L

    /**
     * 追加一条，返回真正落进账本的那行（`seq` / `ts` 已补好）。
     *
     * - `entry.seq == 0` → 用自增计数器；非 0 → 尊重调用方。从磁盘恢复账本时靠这一点保住原来的
     *   seq，同时把计数器抬到它之后，避免新行和旧行重号。
     * - `entry.ts == 0` → 补 `System.currentTimeMillis()`；调用方给了就不覆盖（补录旧账要用原时间）。
     */
    fun add(entry: JournalEntry): JournalEntry = synchronized(lock) {
        val seq = if (entry.seq > 0L) entry.seq else nextSeq
        if (seq >= nextSeq) nextSeq = seq + 1L
        val stored = entry.copy(
            seq = seq,
            ts = if (entry.ts > 0L) entry.ts else System.currentTimeMillis(),
        )
        items.add(stored)
        while (items.size > limit) items.removeAt(0)
        stored
    }

    /** 旧 -> 新，读账本的自然顺序（也是落盘的顺序）。 */
    fun snapshot(): List<JournalEntry> = synchronized(lock) { items.toList() }

    /** 新 -> 旧，UI 列表默认顺序。 */
    fun newestFirst(): List<JournalEntry> = synchronized(lock) { items.reversed() }

    /**
     * 丢掉所有行，但**不**重置 seq 计数器：还原是一次性动作，重号会让「这一行做过没有」失去
     * 意义，而计数器本身不占空间。
     */
    fun clear() {
        synchronized(lock) { items.clear() }
    }

    val size: Int get() = synchronized(lock) { items.size }

    /** `reversible && ok` 的条数——这才是「现在真的能撤回几件事」。 */
    val undoableCount: Int get() = synchronized(lock) { items.count { it.undoable } }

    /**
     * 按 op 去重后的可还原摘要，给 UI 用：`["冻结 ×3", "网络限制 ×2"]`，顺序按第一次出现。
     * 只统计 `reversible && ok` 的行——失败的那次没有改动可撤，不可还原的 op 撤了会变成新的改动。
     */
    fun undoableSummary(): List<String> = synchronized(lock) {
        val counts = LinkedHashMap<EffectOp, Int>()
        for (entry in items) {
            if (!entry.undoable) continue
            counts[entry.op] = (counts[entry.op] ?: 0) + 1
        }
        counts.map { (op, count) -> "${op.label} ×$count" }
    }
}
