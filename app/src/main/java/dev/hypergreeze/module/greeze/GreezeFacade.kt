package dev.hypergreeze.module.greeze

import android.content.Context
import dev.hypergreeze.core.FreezeState
import dev.hypergreeze.core.ProcessRow
import dev.hypergreeze.module.Logx
import dev.hypergreeze.module.R
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong

/**
 * Typed wrapper around the reflection handles in [GreezeRefs].
 *
 * Rules that apply to every call:
 *  1. argument types are coerced to the *declared* parameter types (`long delay` vs `int delay`
 *     differs between HyperOS builds);
 *  2. nothing throws upwards — a failure returns `null` / a default and bumps [errorCount];
 *  3. **`getInstance()` is never called**: it is a "create if null" factory in this ROM, so calling
 *     it early would install a half-built service into `sInstance`. The instance comes from the
 *     constructor hook, from `init(Context)`, from the `sInstance` field, or from
 *     `ServiceManager.getService("greeze")` (the service publishes itself under that name).
 */
class GreezeFacade(val refs: GreezeRefs) {

    @Volatile
    private var instance: Any? = null

    @Volatile
    private var context: Context? = null

    val errorCount = AtomicLong()

    @Volatile
    var instanceOrigin: String? = null
        private set

    val hasInstance: Boolean get() = resolveInstance() != null
    val hasContext: Boolean get() = resolveContext() != null

    /** Called from the constructor hook: the most reliable way to learn the singleton. */
    fun captureInstance(obj: Any?, origin: String = "constructor-hook") {
        if (obj == null) return
        if (instance !== obj) {
            instance = obj
            instanceOrigin = origin
        }
    }

    fun captureContext(ctx: Any?) {
        if (ctx is Context && context !== ctx) context = ctx
    }

    fun resolveContext(): Context? {
        context?.let { return it }
        // GreeZe keeps its own context; find it without knowing the exact field name.
        val obj = resolveInstance()
        if (obj != null) {
            for (f in R.allFields(obj.javaClass)) {
                if (!Context::class.java.isAssignableFrom(f.type)) continue
                val value = R.readField(obj, f)
                if (value is Context) {
                    context = value
                    return value
                }
            }
        }
        return null
    }

    fun resolveInstance(): Any? {
        instance?.let { return it }
        val cls = refs.managerClass

        // 1) the volatile singleton field, read-only, no side effects
        refs.f("sInstance")?.let { field ->
            val value = R.readField(null, field)
            if (value != null && cls.isInstance(value)) {
                instance = value
                instanceOrigin = "static-field:sInstance"
                return value
            }
        }

        // 2) ServiceManager: Lifecycle.onStart() does publishBinderService(SERVICE_NAME, sInstance),
        //    so in-process this returns the very same object.
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, SERVICE_NAME)
            if (binder != null && cls.isInstance(binder)) {
                instance = binder
                instanceOrigin = "ServiceManager:$SERVICE_NAME"
                return binder
            }
        } catch (_: Throwable) {
            // ignore
        }

        // 3) getService() == (GreezeManagerService) IGreezeManagerSingleton.get()
        val getter = refs.m("getService")
        if (getter != null && Modifier.isStatic(getter.modifiers)) {
            val value = try {
                getter.invoke(null)
            } catch (_: Throwable) {
                null
            }
            if (value != null && cls.isInstance(value)) {
                instance = value
                instanceOrigin = "static-method:getService"
                return value
            }
        }

        // 4) any static field of the manager's own type
        for (f in R.allFields(cls)) {
            if (!Modifier.isStatic(f.modifiers)) continue
            if (!cls.isAssignableFrom(f.type)) continue
            val value = R.readField(null, f)
            if (value != null && cls.isInstance(value)) {
                instance = value
                instanceOrigin = "static-field:${f.name}"
                return value
            }
        }
        return null
    }

    // ------------------------------------------------------------------ invocation plumbing

    private fun targetFor(m: Method): Any? = if (Modifier.isStatic(m.modifiers)) null else resolveInstance()

    private fun coerce(args: Array<out Any?>, types: Array<Class<*>>): Array<Any?> =
        Array(args.size) { i ->
            val value = args[i]
            val type = types.getOrNull(i) ?: return@Array value
            when {
                value == null -> null
                type == Int::class.javaPrimitiveType && value is Long -> value.toInt()
                type == Long::class.javaPrimitiveType && value is Int -> value.toLong()
                type == Boolean::class.javaPrimitiveType && value is Int -> value != 0
                type == Int::class.javaPrimitiveType && value is Boolean -> if (value) 1 else 0
                else -> value
            }
        }

    private fun invokeRaw(m: Method, vararg args: Any?): Any? {
        val target = targetFor(m)
        if (target == null && !Modifier.isStatic(m.modifiers)) return null
        return try {
            m.invoke(target, *coerce(args, m.parameterTypes))
        } catch (t: Throwable) {
            errorCount.incrementAndGet()
            Logx.w("invoke ${m.name} failed", t)
            null
        }
    }

    private fun call(key: String, vararg args: Any?): Any? = refs.m(key)?.let { invokeRaw(it, *args) }

    /** For `void` methods: true when the invocation actually went through. */
    private fun callUnit(key: String, vararg args: Any?): Boolean {
        val m = refs.m(key) ?: return false
        val target = targetFor(m)
        if (target == null && !Modifier.isStatic(m.modifiers)) return false
        return try {
            m.invoke(target, *coerce(args, m.parameterTypes))
            true
        } catch (t: Throwable) {
            errorCount.incrementAndGet()
            Logx.w("invoke ${m.name} failed", t)
            false
        }
    }

    // ------------------------------------------------------------------ freeze / thaw

    fun freezeUid(uid: Int, delayMs: Long, reason: Int, tag: String, abnormalType: Int = 0): String? =
        R.asString(call("freezeUid", uid, delayMs, reason, tag, abnormalType))

    fun freezeUids(uids: IntArray, delayMs: Long, reason: Int, tag: String, freezeByUid: Boolean = true): List<*>? =
        call("freezeUids", uids, delayMs, reason, tag, freezeByUid) as? List<*>

    fun freezePids(pids: IntArray, delayMs: Long, reason: Int, tag: String): List<*>? =
        call("freezePids", pids, delayMs, reason, tag) as? List<*>

    fun freezeAction(uid: Int, fromWho: Int, reason: String, needCompact: Boolean, freezeByUid: Boolean): Boolean =
        R.asBool(call("freezeAction", uid, fromWho, reason, needCompact, freezeByUid))

    fun thawUid(uid: Int, reason: Int = 0, tag: String = "hypergreeze"): Boolean =
        R.asBool(call("thawUid", uid, reason, tag))

    fun thawUids(uids: IntArray, reason: Int = 0, tag: String = "hypergreeze"): List<*>? =
        call("thawUids", uids, reason, tag) as? List<*>

    fun thawPids(pids: IntArray, reason: Int = 0, tag: String = "hypergreeze"): List<*>? =
        call("thawPids", pids, reason, tag) as? List<*>

    /** Both overloads exist in this ROM: `thawAll(int,int,String):List` and `thawAll(String):boolean`. */
    fun thawAll(reason: Int = 0, tag: String = "hypergreeze"): Boolean {
        refs.m("thawAll3")?.let { method ->
            val value = invokeRaw(method, reason, tag)
            if (value is List<*>) return true
        }
        refs.m("thawAll1")?.let { method ->
            val value = invokeRaw(method, tag)
            if (value is Boolean) return value
        }
        refs.m("thawuidsAll")?.let { method ->
            return callUnit("thawuidsAll", tag)
        }
        return false
    }

    fun thawUidsByType(type: Int): Boolean = callUnit("thawUidsByType", type)

    // ------------------------------------------------------------------ state

    fun freezeState(uid: Int): FreezeState {
        val value = call("getFreezeState", uid) ?: return FreezeState.UNKNOWN
        if (value is Enum<*>) {
            // Ordinal order is verified as UNFROZEN/PARTIALLY_FROZEN/FULLY_FROZEN (0/1/2), but the
            // enum's own `getValue()` is preferred when present.
            val declared = try {
                value.javaClass.getMethod("getValue").invoke(value) as? Int
            } catch (_: Throwable) {
                null
            }
            return FreezeState.fromOrdinal(declared ?: value.ordinal)
        }
        if (value is Int) return FreezeState.fromOrdinal(value)
        return FreezeState.fromName(value.toString())
    }

    fun isUidFrozen(uid: Int): Boolean? =
        if (refs.m("isUidFrozen") == null) null else R.asBool(call("isUidFrozen", uid))

    fun isUidPartiallyFrozen(uid: Int): Boolean? =
        if (refs.m("isUidPartiallyFrozen") == null) null else R.asBool(call("isUidPartiallyFrozen", uid))

    /**
     * @param module GreeZe's own module selector (0/1/2/9999 = all), **not** a user id — the
     * signature is `getFrozenUids(int module): int[]`.
     */
    fun frozenUids(module: Int = 0): IntArray {
        val value = call("getFrozenUids", module) ?: return IntArray(0)
        return when (value) {
            is IntArray -> value
            is Collection<*> -> value.filterIsInstance<Int>().toIntArray()
            else -> IntArray(0)
        }
    }

    fun lastThawedTime(uid: Int, pid: Int = 0): Long = R.asLong(call("getLastThawedTime", uid, pid))

    fun checkReason(uid: Int): String? = R.asString(call("getAurogonCheckReason", uid))

    fun packagesOfUid(uid: Int): Array<String> {
        val value = call("getPackageNamesFromUid", uid) ?: return emptyArray()
        return when (value) {
            is Array<*> -> value.filterIsInstance<String>().toTypedArray()
            is Collection<*> -> value.filterIsInstance<String>().toTypedArray()
            else -> emptyArray()
        }
    }

    fun processesOfUid(uid: Int): List<ProcessRow> {
        val value = call("getProcessByUid", uid) as? Collection<*> ?: return emptyList()
        return value.mapNotNull { item -> item?.let(::rowOf) }
    }

    /** Projects GreeZe's `RunningProcess` (or anything shaped like it) onto [ProcessRow]. */
    fun rowOf(item: Any?): ProcessRow? {
        if (item == null) return null
        var pid = 0
        var uid = 0
        var name: String? = null
        for (f in R.allFields(item.javaClass)) {
            val lower = f.name.lowercase()
            when {
                lower == "pid" || lower == "mpid" -> pid = R.asInt(R.readField(item, f), pid)
                lower == "uid" || lower == "muid" -> uid = R.asInt(R.readField(item, f), uid)
                lower.contains("processname") || lower.contains("procname") ->
                    name = R.readField(item, f) as? String ?: name
            }
        }
        val resolved = name ?: return null
        return ProcessRow(pid = pid, uid = uid, name = resolved)
    }

    // ------------------------------------------------------------------ whitelist predicates

    fun isTempWhite(pkg: String): Boolean? =
        if (refs.m("isTempWhite") == null) null else R.asBool(call("isTempWhite", pkg))

    fun isNoRestrictApp(pkg: String): Boolean? =
        if (refs.m("isNoRestrictApp") == null) null else R.asBool(call("isNoRestrictApp", pkg))

    fun isAllowWakeUpList(pkg: String): Boolean? =
        if (refs.m("isAllowWakeUpList") == null) null else R.asBool(call("isAllowWakeUpList", pkg))

    // ------------------------------------------------------------------ resources

    fun isJobRestrict(uid: Int): Boolean? =
        if (refs.m("isJobRestrict") == null) null else R.asBool(call("isJobRestrict", uid))

    /** `jobRestrict(int)` returns void on this ROM — success means "the call landed". */
    fun setJobRestrict(uid: Int): Boolean = callUnit("jobRestrict", uid)

    /** Orphan method (present in method_ids, absent from class_data): name+param matching only. */
    fun setWakeLockLimit(uid: Int, limit: Boolean): Boolean = callUnit("updateWakeLockLimit", uid, limit)

    fun setUdpRestrict(uid: Int, restrict: Boolean): Boolean = callUnit("udpPackageRestrict", uid, restrict)

    fun isFreezeBinderEnabled(): Boolean? =
        if (refs.m("getFreezeBinderEnable") == null) null else R.asBool(call("getFreezeBinderEnable"))

    fun setFreezeBinderEnabled(enabled: Boolean): Boolean =
        callUnit("setFreezeBinderEnableCloud", enabled)

    // ------------------------------------------------------------------ modes

    fun triggerQuickFreezeMode(mode: String, uid: Int, reason: String): Boolean =
        callUnit("triggerQuickFreezeMode", mode, uid, reason)

    fun triggerLaunchMode(mode: String, uid: Int): Boolean = callUnit("triggerLaunchMode", mode, uid)

    fun triggerIdleAppAction(uid: Int): Boolean = callUnit("triggerIdleAppAction", uid)

    // ------------------------------------------------------------------ list fields

    fun listFieldSnapshot(key: String): List<String> {
        val f = refs.f(key) ?: return emptyList()
        val value = R.readField(resolveInstance(), f) ?: return emptyList()
        return when (value) {
            is Collection<*> -> value.filterIsInstance<String>()
            is Array<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
    }

    fun listFieldSnapshotOn(key: String, owner: Any?): List<String> {
        val f = refs.f(key) ?: return emptyList()
        val value = R.readField(owner, f) ?: return emptyList()
        return when (value) {
            is Collection<*> -> value.filterIsInstance<String>()
            is Array<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
    }

    /**
     * Optional (opt-in) mutation of GreeZe's own list fields. Used only when
     * `global.patchLists = true`, because the hook layer normally patches the *consumption* point
     * instead — cloud-pushed lists would overwrite anything written here.
     */
    fun patchListField(key: String, extra: Collection<String>, remove: Collection<String> = emptyList()): Boolean {
        val f = refs.f(key) ?: return false
        val target = if (Modifier.isStatic(f.modifiers)) null else resolveInstance()
        if (target == null && !Modifier.isStatic(f.modifiers)) return false
        val current = R.readField(target, f) ?: return false
        return try {
            @Suppress("UNCHECKED_CAST")
            when (current) {
                is MutableSet<*> -> {
                    val set = current as MutableSet<Any?>
                    set.addAll(extra)
                    set.removeAll(remove.toSet())
                    true
                }
                is MutableList<*> -> {
                    val list = current as MutableList<Any?>
                    for (item in extra) if (item !in list) list.add(item)
                    list.removeAll(remove.toSet())
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            errorCount.incrementAndGet()
            false
        }
    }

    fun dumpField(key: String): String {
        val f: Field = refs.f(key) ?: return "(field $key missing)"
        val value = R.readField(if (Modifier.isStatic(f.modifiers)) null else resolveInstance(), f)
        return "$key=${value?.javaClass?.simpleName ?: "null"}:$value"
    }

    companion object {
        /** Strong inference from the bytecode: the only candidate for `SERVICE_NAME`. */
        const val SERVICE_NAME = "greeze"
    }
}
