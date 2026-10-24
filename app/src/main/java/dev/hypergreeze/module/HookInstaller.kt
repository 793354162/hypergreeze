package dev.hypergreeze.module

import android.os.SystemClock
import dev.hypergreeze.core.EventType
import dev.hypergreeze.core.FreezeMode
import dev.hypergreeze.core.HookState
import dev.hypergreeze.core.HookStatus
import dev.hypergreeze.core.Module
import dev.hypergreeze.core.SceneBit
import dev.hypergreeze.module.greeze.GreezeFacade
import dev.hypergreeze.module.greeze.GreezeRefs
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Installs and owns every hook.
 *
 * Contract with the rest of the module:
 *  - **never throw upwards.** Every body computes the stock result first, then applies policy
 *    inside a `try/catch` whose fallback is that stock result.
 *  - **never freeze on our own authority while [passive].** Safe mode installs the hooks anyway so
 *    the diagnostics page can show *why* it is passive, but every body becomes a pass-through.
 *  - **calls into GreeZe are funnelled through [GreezeFacade]**, which never throws either.
 *
 * Hook ids are stable strings: they show up verbatim on the diagnostics page and in the hook log.
 */
class HookInstaller(
    private val module: XposedModule,
    private val refs: GreezeRefs,
    private val facade: GreezeFacade,
    private val engineProvider: () -> PolicyEngine,
    private val work: android.os.Handler,
) {

    /** Set once a `Context` is available; until then hooks run without telemetry. */
    @Volatile
    var telemetry: ModuleTelemetry? = null

    /** Called when GreeZe's own `init(Context)` ran, i.e. the service is fully alive. */
    @Volatile
    var onGreezeReady: (() -> Unit)? = null

    @Volatile
    var passive: Boolean = false

    private val handles = ArrayList<HookHandle>()
    private val statuses = LinkedHashMap<String, HookStatus>()
    private val counters = ConcurrentHashMap<String, Counter>()
    private val uidPackages = ConcurrentHashMap<Int, List<String>>()
    private val pendingFreeze = ConcurrentHashMap.newKeySet<Int>()
    private val lastAttempt = ConcurrentHashMap<Int, Long>()
    private val resourceAppliedRev = ConcurrentHashMap<Int, Long>()

    /**
     * Which argument of `onForegroundActivitiesChanged(int, int, boolean)` is the uid. AOSP passes
     * `(pid, uid, foreground)`; the GreeZe inner class is not required to keep that order and the
     * bytecode does not tell us. Resolved empirically on first useful observation.
     */
    @Volatile
    private var uidArgIndex: Int = -1

    private class Counter {
        val hits = AtomicLong()
        val errors = AtomicLong()
    }

    // ------------------------------------------------------------------ install

    fun install(): List<HookStatus> {
        installInstanceCapture()
        installFilter()
        installWhitelists()
        installFreezeUid()
        installForeground()
        installScreenOnThaw()
        return snapshot()
    }

    private fun install(
        id: String,
        target: String,
        method: Method?,
        body: (Chain) -> Any?,
    ): HookStatus {
        if (method == null) {
            val status = HookStatus(id, target, HookState.MISSING)
            statuses[id] = status
            Logx.w("hook $id: target missing ($target)")
            return status
        }
        return try {
            val handle = module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(id)
                .intercept { chain -> body(chain) }
            handles.add(handle)
            val status = HookStatus(id, target, HookState.INSTALLED)
            statuses[id] = status
            Logx.i("hook $id -> installed ($target)")
            status
        } catch (t: Throwable) {
            val status = HookStatus(id, target, HookState.ERROR, note = t.toString())
            statuses[id] = status
            Logx.e("hook $id -> failed ($target)", t)
            status
        }
    }

    private fun installInstanceCapture() {
        val constructors = try {
            refs.managerClass.declaredConstructors
        } catch (_: Throwable) {
            emptyArray()
        }
        for ((index, ctor) in constructors.withIndex()) {
            val id = "$ID_CAPTURE_CTOR-$index"
            try {
                val handle = module.hook(ctor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId(id)
                    .intercept { chain -> bodyConstructor(chain) }
                handles.add(handle)
                statuses[id] = HookStatus(id, "GreezeManagerService.<init>", HookState.INSTALLED)
            } catch (t: Throwable) {
                statuses[id] = HookStatus(id, "GreezeManagerService.<init>", HookState.ERROR, note = t.toString())
                Logx.w("constructor capture hook failed", t)
            }
        }
        install(ID_CAPTURE_INIT, "GreezeManagerService.init(Context)", refs.m("init")) { chain ->
            bodyInit(chain)
        }
    }

    private fun installFilter() {
        val method = refs.filterClass?.let { R.method(it, "filter", 3) }
            ?: R.method(refs.managerClass, "filter", 3)
        install(ID_FILTER, "AurogonFilterManager.filter(int,String,int):boolean", method) { chain ->
            bodyFilter(chain)
        }
    }

    private fun installWhitelists() {
        install(
            ID_NO_RESTRICT,
            "AurogonImmobulusMode.isNoRestrictApp(String):boolean",
            refs.m("isNoRestrictApp"),
        ) { chain -> bodyWhitelist(chain, ID_NO_RESTRICT) }

        install(
            ID_WAKEUP,
            "AurogonImmobulusMode.isAllowWakeUpList(String):boolean",
            refs.m("isAllowWakeUpList"),
        ) { chain -> bodyWakeUp(chain) }

        install(
            ID_TEMP_WHITE,
            "GreezeManagerService.isTempWhite(String):boolean",
            refs.m("isTempWhite"),
        ) { chain -> bodyWhitelist(chain, ID_TEMP_WHITE) }
    }

    private fun installFreezeUid() {
        install(
            ID_FREEZE_UID,
            "GreezeManagerService.freezeUid(int,long,int,String,int):String",
            refs.m("freezeUid"),
        ) { chain -> bodyFreezeUid(chain) }

        install(
            ID_FREEZE_UIDS,
            "GreezeManagerService.freezeUids(int[],long,int,String,boolean):List",
            refs.m("freezeUids"),
        ) { chain -> bodyFreezeUids(chain) }

        install(
            ID_FREEZE_PROCESS,
            "GreezeManagerService.freezeProcess(RunningProcess,long,int,String):boolean",
            refs.m("freezeProcess"),
        ) { chain -> bodyFreezeProcess(chain) }
    }

    /**
     * The only freeze path that carries a *process name*, so it is also the only place where the
     * per-process overrides (`AppPolicy.processes`) can actually be honoured.
     */
    private fun bodyFreezeProcess(chain: Chain): Any? {
        var blocked: dev.hypergreeze.core.ProcessRow? = null
        try {
            hit(ID_FREEZE_PROCESS)
            val engine = engineProvider()
            if (!passive && engine.enabled) {
                val row = facade.rowOf(chain.getArg(0))
                if (row != null) {
                    val pkg = packageFor(row.uid)
                    if (pkg != null && engine.neverFreeze(pkg, row.name)) blocked = row
                }
            }
        } catch (t: Throwable) {
            hit(ID_FREEZE_PROCESS, error = true)
            Logx.w("freezeProcess pre-check failed", t)
        }
        val row = blocked
        if (row != null) {
            telemetry?.record(
                type = EventType.SKIP,
                uid = row.uid,
                pkg = row.name,
                result = Module.RESULT_SKIP_BY_MOD,
                detail = "process override never",
            )
            return false
        }
        return chain.proceed()
    }

    private fun installForeground() {
        install(
            ID_FOREGROUND,
            "GreezeManagerService\$13.onForegroundActivitiesChanged(int,int,boolean)",
            refs.m("onForegroundActivitiesChanged"),
        ) { chain -> bodyForeground(chain) }
    }

    private fun installScreenOnThaw() {
        install(
            ID_SCREEN_ON,
            "GreezeManagerService.needThawWhenScreenOn(int):boolean",
            refs.m("needThawWhenScreenOn"),
        ) { chain -> bodyScreenOn(chain) }
    }

    // ------------------------------------------------------------------ bodies

    private fun bodyConstructor(chain: Chain): Any? {
        val result = chain.proceed()
        try {
            facade.captureInstance(chain.thisObject, "constructor-hook")
        } catch (t: Throwable) {
            Logx.w("capture instance from constructor failed", t)
        }
        return result
    }

    private fun bodyInit(chain: Chain): Any? {
        val result = chain.proceed()
        try {
            facade.captureInstance(chain.thisObject, "init-hook")
            if (chain.args.isNotEmpty()) facade.captureContext(chain.getArg(0))
            Logx.i("GreeZe init() observed; instance=${facade.instanceOrigin} context=${facade.hasContext}")
            onGreezeReady?.invoke()
        } catch (t: Throwable) {
            Logx.w("init hook body failed", t)
        }
        return result
    }

    private fun bodyFilter(chain: Chain): Any? {
        val raw = chain.proceed()
        val original = raw as? Boolean ?: return raw
        return try {
            hit(ID_FILTER)
            val engine = engineProvider()
            if (passive || !engine.enabled) return original
            val uid = R.asInt(chain.getArg(0))
            val pkg = (chain.getArg(1) as? String) ?: packageFor(uid) ?: return original
            val reason = R.asInt(chain.getArg(2))
            val verdict = engine.filterVerdict(pkg, reason, original)
            if (verdict != original) {
                telemetry?.record(
                    type = if (verdict) EventType.INFO else EventType.SKIP,
                    uid = uid,
                    pkg = pkg,
                    reason = reason,
                    result = verdict.toString(),
                    detail = "filter override",
                )
            }
            verdict
        } catch (t: Throwable) {
            hit(ID_FILTER, error = true)
            Logx.w("filter body failed", t)
            original
        }
    }

    private fun bodyWhitelist(chain: Chain, id: String): Any? {
        val raw = chain.proceed()
        val original = raw as? Boolean ?: return raw
        return try {
            hit(id)
            val engine = engineProvider()
            if (passive || !engine.enabled) return original
            val pkg = chain.getArg(0) as? String ?: return original
            val verdict = engine.whitelistVerdict(pkg, original)
            if (verdict != original) {
                telemetry?.record(
                    type = EventType.INFO,
                    pkg = pkg,
                    result = verdict.toString(),
                    detail = id,
                )
            }
            verdict
        } catch (t: Throwable) {
            hit(id, error = true)
            Logx.w("$id body failed", t)
            original
        }
    }

    private fun bodyWakeUp(chain: Chain): Any? {
        val raw = chain.proceed()
        val original = raw as? Boolean ?: return raw
        return try {
            hit(ID_WAKEUP)
            val engine = engineProvider()
            if (passive || !engine.enabled) return original
            val pkg = chain.getArg(0) as? String ?: return original
            engine.wakeUpVerdict(pkg, original)
        } catch (t: Throwable) {
            hit(ID_WAKEUP, error = true)
            Logx.w("wakeup body failed", t)
            original
        }
    }

    private fun bodyFreezeUid(chain: Chain): Any? {
        var blockedFor: String? = null
        try {
            hit(ID_FREEZE_UID)
            val engine = engineProvider()
            if (!passive && engine.enabled) {
                val uid = R.asInt(chain.getArg(0))
                blockedFor = packagesFor(uid).firstOrNull { engine.neverFreeze(it) }
            }
        } catch (t: Throwable) {
            hit(ID_FREEZE_UID, error = true)
            Logx.w("freezeUid pre-check failed", t)
        }
        if (blockedFor != null) {
            val uid = R.asInt(chain.getArg(0))
            val reason = R.asInt(chain.getArg(2))
            Logx.i("blocking freezeUid uid=$uid pkg=$blockedFor reason=$reason")
            telemetry?.record(
                type = EventType.SKIP,
                uid = uid,
                pkg = blockedFor,
                reason = reason,
                result = Module.RESULT_SKIP_BY_MOD,
                detail = "never-freeze policy",
            )
            telemetry?.flushEvents()
            // Skipping `proceed()` is what actually prevents the cgroup write; the returned string
            // only tells GreeZe "not frozen" so its own state machine stays consistent.
            return Module.RESULT_SKIP_BY_MOD
        }
        val result = chain.proceed()
        try {
            val engine = engineProvider()
            if (!passive && engine.enabled && engine.policy.global.audit) {
                val uid = R.asInt(chain.getArg(0))
                telemetry?.record(
                    type = if (result is String && result == "OK") EventType.FREEZE else EventType.INFO,
                    uid = uid,
                    pkg = packageFor(uid),
                    reason = R.asInt(chain.getArg(2)),
                    result = result as? String,
                    detail = "freezeUid",
                )
            }
        } catch (t: Throwable) {
            Logx.w("freezeUid audit failed", t)
        }
        return result
    }

    /**
     * Batch path. GreeZe has several freeze entry points; `freezeUid` covers the single-uid ones
     * (and `freezeAction`, which funnels into it), while `freezeUids` takes an array and may write
     * the cgroup per uid without going through `freezeUid`. Filtering the array is therefore the
     * only way a NEVER policy holds on this path.
     *
     * The pid-level entry points (`freezePids` / `freezeProcess`) are intentionally not filtered:
     * they carry no uid, so a policy lookup would be a guess.
     */
    private fun bodyFreezeUids(chain: Chain): Any? {
        val uids = chain.getArg(0) as? IntArray ?: return chain.proceed()
        return try {
            hit(ID_FREEZE_UIDS)
            val engine = engineProvider()
            if (passive || !engine.enabled || uids.isEmpty()) return chain.proceed()
            val kept = uids.filter { uid -> packagesFor(uid).none { engine.neverFreeze(it) } }
            if (kept.size == uids.size) return chain.proceed()
            val blocked = uids.filter { it !in kept }
            telemetry?.record(
                type = EventType.SKIP,
                uid = blocked.firstOrNull() ?: 0,
                pkg = blocked.firstOrNull()?.let { packageFor(it) },
                result = Module.RESULT_SKIP_BY_MOD,
                detail = "freezeUids filtered ${blocked.size}/${uids.size}",
            )
            if (kept.isEmpty()) return emptyList<Int>()
            val args = chain.args.toTypedArray()
            args[0] = kept.toIntArray()
            chain.proceed(args)
        } catch (t: Throwable) {
            hit(ID_FREEZE_UIDS, error = true)
            Logx.w("freezeUids body failed", t)
            chain.proceed()
        }
    }

    private fun bodyForeground(chain: Chain): Any? {
        val raw = chain.proceed()
        try {
            hit(ID_FOREGROUND)
            if (passive) return raw
            val a0 = R.asInt(chain.getArg(0))
            val a1 = R.asInt(chain.getArg(1))
            val foreground = R.asBool(chain.getArg(2), true)
            if (!foreground) {
                val uid = resolveUid(a0, a1)
                val pid = if (uid == a0) a1 else a0
                onBackground(uid, pid)
            }
        } catch (t: Throwable) {
            hit(ID_FOREGROUND, error = true)
            Logx.w("foreground body failed", t)
        }
        return raw
    }

    private fun bodyScreenOn(chain: Chain): Any? {
        val raw = chain.proceed()
        val original = raw as? Boolean ?: return raw
        return try {
            hit(ID_SCREEN_ON)
            val engine = engineProvider()
            if (passive || !engine.enabled) return original
            val uid = R.asInt(chain.getArg(0))
            packagesFor(uid)
                .firstNotNullOfOrNull { engine.thawTrigger("screenOn", it) }
                ?: original
        } catch (t: Throwable) {
            hit(ID_SCREEN_ON, error = true)
            original
        }
    }

    // ------------------------------------------------------------------ forced freeze

    private fun onBackground(uid: Int, pid: Int) {
        val engine = engineProvider()
        if (!engine.enabled) return
        val target = packagesFor(uid).firstOrNull { engine.forceFreezeOnBackground(it) } ?: return
        if (!pendingFreeze.add(uid)) return
        val delay = engine.delayMs(target)
        val task = Runnable {
            pendingFreeze.remove(uid)
            forcedFreeze(uid, pid, target)
        }
        if (delay > 0L) work.postDelayed(task, delay) else work.post(task)
    }

    private fun forcedFreeze(uid: Int, pid: Int, pkg: String) {
        try {
            val engine = engineProvider()
            if (passive || !engine.enabled || !engine.forceFreezeOnBackground(pkg)) return
            if (facade.isUidFrozen(uid) == true) {
                telemetry?.record(EventType.SKIP, uid, pkg, Module.REASON_MOD, "already-frozen", "forced")
                return
            }
            val now = SystemClock.elapsedRealtime()
            val cooldown = engine.cooldownMs(pkg)
            val last = lastAttempt[uid] ?: 0L
            if (cooldown > 0L && last != 0L && now - last < cooldown) {
                telemetry?.record(EventType.SKIP, uid, pkg, Module.REASON_MOD, "cooldown", "forced")
                return
            }
            lastAttempt[uid] = now
            applyResources(uid, pkg)
            val result = facade.freezeUid(uid, 0L, Module.REASON_MOD, Module.FREEZE_TAG, 0)
            telemetry?.record(EventType.FREEZE, uid, pkg, Module.REASON_MOD, result, "forced pid=$pid")
            telemetry?.flushEvents()
            Logx.i("forced freeze uid=$uid pkg=$pkg result=$result")
        } catch (t: Throwable) {
            Logx.w("forcedFreeze failed uid=$uid", t)
        }
    }

    /**
     * Applies the resource switches that have a *verified* setter. `isRestrictReceiver` is
     * deliberately not hooked: its five arguments do not identify the package unambiguously, and a
     * wrong guess would break background broadcasts globally instead of per app.
     */
    private fun applyResources(uid: Int, pkg: String) {
        val engine = engineProvider()
        val revision = engine.policy.revision
        if (resourceAppliedRev[uid] == revision) return
        resourceAppliedRev[uid] = revision
        engine.resource("net", pkg)?.let { facade.setUdpRestrict(uid, it) }
        engine.resource("job", pkg)?.let { if (it) facade.setJobRestrict(uid) }
        engine.resource("wakelock", pkg)?.let { facade.setWakeLockLimit(uid, it) }
    }

    // ------------------------------------------------------------------ helpers

    private fun hit(id: String, error: Boolean = false) {
        val counter = counters.computeIfAbsent(id) { Counter() }
        if (error) counter.errors.incrementAndGet() else counter.hits.incrementAndGet()
    }

    private fun packagesFor(uid: Int): List<String> {
        if (uidPackages.size > 1024) uidPackages.clear()
        return uidPackages.computeIfAbsent(uid) {
            try {
                facade.packagesOfUid(uid).toList()
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    private fun packageFor(uid: Int): String? = packagesFor(uid).firstOrNull()

    private fun resolveUid(a0: Int, a1: Int): Int {
        val known = uidArgIndex
        if (known == 0) return a0
        if (known == 1) return a1
        val has0 = packagesFor(a0).isNotEmpty()
        val has1 = packagesFor(a1).isNotEmpty()
        return when {
            has0 && !has1 -> {
                uidArgIndex = 0
                Logx.i("foreground callback: uid is arg0")
                a0
            }
            has1 && !has0 -> {
                uidArgIndex = 1
                Logx.i("foreground callback: uid is arg1")
                a1
            }
            // AOSP order is (pid, uid, foreground): fall back to the second argument.
            else -> a1
        }
    }

    // ------------------------------------------------------------------ state

    fun snapshot(): List<HookStatus> = statuses.map { (id, status) ->
        val counter = counters[id]
        val extra = if (id == ID_FOREGROUND) {
            "uid=${uidArgDecision()}"
        } else {
            null
        }
        val note = when {
            passive -> "safe mode: passive"
            status.note != null -> status.note
            else -> extra
        }
        status.copy(
            hits = counter?.hits?.get() ?: 0L,
            errors = counter?.errors?.get() ?: 0L,
            note = note,
        )
    }

    fun totalHits(): Long = counters.values.sumOf { it.hits.get() }

    fun totalErrors(): Long = counters.values.sumOf { it.errors.get() }

    fun unhookAll() {
        for (handle in handles) {
            try {
                handle.unhook()
            } catch (t: Throwable) {
                Logx.w("unhook failed", t)
            }
        }
        handles.clear()
        Logx.i("all hooks removed")
    }

    fun uidArgDecision(): String = when (uidArgIndex) {
        0 -> "arg0"
        1 -> "arg1"
        else -> "unresolved"
    }

    fun invalidateUidCache() {
        uidPackages.clear()
    }

    companion object {
        const val ID_CAPTURE_CTOR = "capture-ctor"
        const val ID_CAPTURE_INIT = "capture-init"
        const val ID_FILTER = "filter"
        const val ID_NO_RESTRICT = "no-restrict"
        const val ID_WAKEUP = "wakeup-list"
        const val ID_TEMP_WHITE = "temp-white"
        const val ID_FREEZE_UID = "freeze-uid"
        const val ID_FREEZE_UIDS = "freeze-uids"
        const val ID_FREEZE_PROCESS = "freeze-process"
        const val ID_FOREGROUND = "foreground"
        const val ID_SCREEN_ON = "screen-on-thaw"

        val ORDER = listOf(
            ID_CAPTURE_INIT,
            ID_FILTER,
            ID_NO_RESTRICT,
            ID_WAKEUP,
            ID_TEMP_WHITE,
            ID_FREEZE_UID,
            ID_FREEZE_UIDS,
            ID_FREEZE_PROCESS,
            ID_FOREGROUND,
            ID_SCREEN_ON,
        )
    }
}
