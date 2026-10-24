package dev.hypergreeze.module

import dev.hypergreeze.core.EffectOp
import dev.hypergreeze.core.JournalEntry
import dev.hypergreeze.core.Module
import dev.hypergreeze.module.greeze.GreezeFacade

/**
 * The single place where the module is allowed to change something outside its own data.
 *
 * Every call that mutates GreeZe state goes through here so the ledger cannot be forgotten by
 * accident — that is the whole point of the class. [revertAll] walks the ledger backwards and
 * undoes what can be undone; whatever cannot (a void `jobRestrict`, a mode trigger) is reported by
 * name instead of being silently ignored.
 *
 * Deliberately **not** journaled:
 *  - automatic freezes triggered by "退后台立即冻结". They happen continuously, would evict real
 *    entries from the 200-slot ring, and are already covered by the `thawAll()` that
 *    [revertAll] performs first.
 */
class Effector(
    private val facade: GreezeFacade,
    private val telemetry: () -> ModuleTelemetry?,
    private val revision: () -> Long,
) {

    /** Set after a revert so nothing re-applies until the user actually changes the policy. */
    @Volatile
    var suppressed: Boolean = false
        private set

    private var suppressedAtRevision: Long = -1L

    fun onPolicyRevision(current: Long) {
        if (suppressed && current != suppressedAtRevision) suppressed = false
    }

    private fun record(
        op: EffectOp,
        target: String,
        uid: Int = 0,
        pkg: String? = null,
        before: String? = null,
        after: String? = null,
        ok: Boolean = true,
        note: String? = null,
    ) {
        telemetry()?.journalAdd(
            JournalEntry(
                op = op,
                target = target,
                uid = uid,
                pkg = pkg,
                before = before,
                after = after,
                ok = ok,
                revision = revision(),
                note = note,
            ),
        )
    }

    private fun targetOf(uid: Int, pkg: String?): String =
        if (pkg.isNullOrBlank()) "uid $uid" else "$pkg (uid $uid)"

    // ------------------------------------------------------------------ freeze / thaw

    fun freeze(uid: Int, pkg: String?, reason: Int, delayMs: Long = 0L): String? {
        val result = facade.freezeUid(uid, delayMs, reason, Module.FREEZE_TAG, 0)
        record(
            op = EffectOp.FREEZE,
            target = targetOf(uid, pkg),
            uid = uid,
            pkg = pkg,
            after = result ?: "null",
            ok = result == "OK",
            note = if (delayMs > 0) "delay=$delayMs" else null,
        )
        return result
    }

    fun thaw(uid: Int, pkg: String?): Boolean {
        val ok = facade.thawUid(uid)
        record(EffectOp.THAW, targetOf(uid, pkg), uid = uid, pkg = pkg, ok = ok)
        return ok
    }

    fun thawAll(): Boolean {
        val ok = facade.thawAll()
        record(EffectOp.THAW, "全部应用", ok = ok, note = "thawAll")
        return ok
    }

    // ------------------------------------------------------------------ resource switches

    fun netRestrict(uid: Int, pkg: String?, restrict: Boolean) {
        if (suppressed) return
        // No getter exists for this one: `before` stays null on purpose.
        val ok = facade.setUdpRestrict(uid, restrict)
        record(
            op = EffectOp.NET_RESTRICT,
            target = targetOf(uid, pkg),
            uid = uid,
            pkg = pkg,
            before = null,
            after = restrict.toString(),
            ok = ok,
            note = if (restrict) "原值不可读" else "放开网络，无法判断原本状态",
        )
    }

    fun jobRestrict(uid: Int, pkg: String?) {
        if (suppressed) return
        val ok = facade.setJobRestrict(uid)
        record(
            op = EffectOp.JOB_RESTRICT,
            target = targetOf(uid, pkg),
            uid = uid,
            pkg = pkg,
            after = "true",
            ok = ok,
            note = "无解除接口",
        )
    }

    fun wakelockLimit(uid: Int, pkg: String?, limit: Boolean) {
        if (suppressed) return
        val before = facade.isWakeLockLimit(uid)?.toString()
        val ok = facade.setWakeLockLimit(uid, limit)
        record(
            op = EffectOp.WAKELOCK_LIMIT,
            target = targetOf(uid, pkg),
            uid = uid,
            pkg = pkg,
            before = before,
            after = limit.toString(),
            ok = ok,
        )
    }

    fun freezeBinder(enabled: Boolean) {
        if (suppressed) return
        val before = facade.isFreezeBinderEnabled()?.toString()
        val ok = facade.setFreezeBinderEnabled(enabled)
        record(
            op = EffectOp.FREEZE_BINDER,
            target = "GreeZe freezeBinder",
            before = before,
            after = enabled.toString(),
            ok = ok,
        )
    }

    fun patchList(field: String, add: Collection<String>, remove: Collection<String> = emptyList()): Boolean {
        if (suppressed) return false
        val ok = facade.patchListField(field, add, remove)
        record(
            op = EffectOp.LIST_PATCH,
            target = field,
            before = remove.joinToString(","),
            after = add.joinToString(","),
            ok = ok,
            note = "add=${add.size} remove=${remove.size}",
        )
        return ok
    }

    fun quickMode(mode: String, uid: Int) {
        val ok = facade.triggerQuickFreezeMode(mode, uid, Module.TAG)
        record(
            op = EffectOp.QUICK_MODE,
            target = "mode=$mode",
            uid = uid,
            after = mode,
            ok = ok,
            note = "无逆操作",
        )
    }

    // ------------------------------------------------------------------ undo

    /**
     * Undo order matters: thaw everything first (covers automatic freezes that were never
     * journaled), then walk the ledger newest-first so repeated restrictions collapse correctly.
     */
    fun revertAll(): String {
        val tm = telemetry() ?: return "no-telemetry"
        val entries = tm.journalEntries().filter { it.ok }
        suppressed = true
        suppressedAtRevision = revision()

        val thawOk = facade.thawAll()
        if (entries.isEmpty()) {
            tm.clearJournal()
            return "没有可还原的改动" + if (thawOk) "（已执行全部解冻）" else "（全部解冻失败）"
        }

        var done = 0
        val skipped = LinkedHashSet<String>()
        for (entry in entries.asReversed()) {
            try {
                when (entry.op) {
                    EffectOp.FREEZE -> {
                        if (facade.thawUid(entry.uid)) done++ else skipped += "解冻失败 uid ${entry.uid}"
                    }

                    EffectOp.WAKELOCK_LIMIT -> {
                        val before = entry.before?.toBooleanStrictOrNull()
                        if (before == null) {
                            skipped += "Wakelock 原值未知（uid ${entry.uid}）"
                        } else if (facade.setWakeLockLimit(entry.uid, before)) {
                            done++
                        } else {
                            skipped += "Wakelock 还原失败（uid ${entry.uid}）"
                        }
                    }

                    EffectOp.FREEZE_BINDER -> {
                        val before = entry.before?.toBooleanStrictOrNull()
                        if (before == null) {
                            skipped += "freezeBinder 原值未知"
                        } else if (facade.setFreezeBinderEnabled(before)) {
                            done++
                        } else {
                            skipped += "freezeBinder 还原失败"
                        }
                    }

                    EffectOp.NET_RESTRICT -> {
                        if (entry.after == "true") {
                            if (facade.setUdpRestrict(entry.uid, false)) {
                                done++
                            } else {
                                skipped += "网络放开失败（uid ${entry.uid}）"
                            }
                        } else {
                            skipped += "网络本就是放开的（uid ${entry.uid}）"
                        }
                    }

                    EffectOp.LIST_PATCH -> {
                        val added = entry.after?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?: emptyList()
                        val removed = entry.before?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?: emptyList()
                        if (added.isEmpty() && removed.isEmpty()) {
                            skipped += "名单改动为空（${entry.target}）"
                        } else if (facade.patchListField(entry.target, removed, added)) {
                            done++
                        } else {
                            skipped += "名单还原失败（${entry.target}）"
                        }
                    }

                    EffectOp.JOB_RESTRICT ->
                        skipped += "Job 限制没有解除接口（重启后由系统重建）"

                    EffectOp.QUICK_MODE ->
                        skipped += "模式触发没有逆操作（${entry.target}）"

                    EffectOp.THAW, EffectOp.REVERT -> Unit
                }
            } catch (t: Throwable) {
                skipped += "${entry.op.label} 异常: ${t.javaClass.simpleName}"
            }
        }

        tm.clearJournal()
        val summary = buildString {
            append("已还原 ").append(done).append(" 项")
            if (skipped.isNotEmpty()) {
                append("；未能还原 ").append(skipped.size).append(" 项：")
                append(skipped.joinToString("、"))
            }
        }
        record(
            op = EffectOp.REVERT,
            target = "revertAll",
            after = "done=$done skipped=${skipped.size}",
            note = summary.take(300),
        )
        tm.flushJournal()
        tm.record(
            dev.hypergreeze.core.EventType.INFO,
            result = "revert",
            detail = summary.take(200),
        )
        tm.flushEvents()
        Logx.i("revert: $summary")
        return summary
    }
}
