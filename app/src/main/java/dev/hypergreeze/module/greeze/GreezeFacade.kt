package dev.hypergreeze.module.greeze

import dev.hypergreeze.core.FreezeState
import dev.hypergreeze.core.ProcessRow
import dev.hypergreeze.module.R
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong

/**
 * Typed wrapper around the reflection handles in [GreezeRefs].
 *
 * Two robustness rules apply to every call:
 *  1. argument types are coerced to the *declared* parameter types before invoking, so an OTA
 *     that changes `long delay` to `int delay` does not break the call;
 *  2. nothing ever throws upwards — a failure returns `null` / a default and bumps [errorCount],
 *     which the diagnostics page shows.
 */
class GreezeFacade(val refs: GreezeRefs) {

    @Volatile
    private var instance: Any? = null

    val errorCount = AtomicLong()
    private var instanceSource: String? = null

    val hasInstance: Boolean get() = resolveInstance() != null
    val instanceOrigin: String? get() = instanceSource

    /** Called from the constructor hook: the most reliable way to learn the singleton. */
    fun captureInstance(obj: Any?) {
        if (obj == null) return
        if (instance !== obj) {
            instance = obj
            instanceSource = "constructor-hook"
        }
    }

    fun resolveInstance(): Any? {
        instance?.let { return it }
        val cls = refs.managerClass

        for (name in R.SINGLETON_FIELDS) {
            val f = R.field(cls, name) ?: continue
            val v = R.readField(null, f)
            if (v != null && cls.isInstance(v)) {
                instance = v
                instanceSource = "static-field:$name"
                return v
            }
        }

        val getter = refs.m("getInstance")
        if (getter != null && Modifier.isStatic(getter.modifiers)) {
            val v = try {
                getter.invoke(null)
            } catch (_: Throwable) {
                null
            }
            if (v != null && cls.isInstance(v)) {
                instance = v
                instanceSource = "static-method:getInstance"
                return v
            }
        }

        for (f in R.allFields(cls)) {
            if (!Modifier.isStatic(f.modifiers)) continue
            if (!cls.isAssignableFrom(f.type)) continue
            val v = R.readField(null, f)
            if (v != null && cls.isInstance(v)) {
                instance = v
                instanceSource = "static-field:${f.name}"
                return v
            }
        }
        return null
    }

    /** The GreeZe context, if the service keeps one — avoids a second context resolution path. */
    fun serviceContext(): Any? {
        val obj = resolveInstance() ?: return null
        val f: Field = refs.f("mContext") ?: return null
        return R.readField(obj, f)
    }

    private fun invokeRaw(m: Method, vararg args: Any?): Any? {
        val target = if (Modifier.isStatic(m.modifiers)) null else resolveInstance()
        if (target == null && !Modifier.isStatic(m.modifiers)) return null
        return try {
            m.invoke(target, *coerce(args, m.parameterTypes))
        } catch (t: Throwable) {
            errorCount.incrementAndGet()
            null
        }
    }

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

    private fun call(key: String, vararg args: Any?): Any? {
        val m = refs.m(key) ?: return null
        return invokeRaw(m, *args)
    }

    // ---------------------------------------------------------------- freeze / thaw

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

    fun thawAll(reason: Int = 0, tag: String = "hypergreeze"): Boolean {
        val twoArg = refs.m("thawAll")
        if (twoArg != null) {
            val value = invokeRaw(twoArg, reason, tag)
            if (value is Boolean) return value
            if (value is List<*>) return true
        }
        val oneArg = refs.m("thawuidsAll") ?: return false
        return R.asBool(invokeRaw(oneArg, tag))
    }

    fun thawUidsByType(type: Int): Boolean =
        call("thawUidsByType", type) != null

    // ---------------------------------------------------------------- state

    fun freezeState(uid: Int): FreezeState {
        val value = call("getFreezeState", uid) ?: return FreezeState.UNKNOWN
        if (value is Enum<*>) return FreezeState.fromOrdinal(value.ordinal)
        if (value is Int) return FreezeState.fromOrdinal(value)
        return FreezeState.fromName(value.toString())
    }

    fun isUidFrozen(uid: Int): Boolean? {
        if (refs.m("isUidFrozen") == null) return null
        return R.asBool(call("isUidFrozen", uid))
    }

    fun isUidPartiallyFrozen(uid: Int): Boolean? {
        if (refs.m("isUidPartiallyFrozen") == null) return null
        return R.asBool(call("isUidPartiallyFrozen", uid))
    }

    fun frozenUids(userId: Int = 0): IntArray {
        val value = call("getFrozenUids", userId) ?: return IntArray(0)
        return when (value) {
            is IntArray -> value
            is Collection<*> -> value.filterIsInstance<Int>().toIntArray()
            else -> IntArray(0)
        }
    }

    fun lastThawedTime(uid: Int, pid: Int = 0): Long =
        R.asLong(call("getLastThawedTime", uid, pid))

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
        return value.mapNotNull { item -> item?.let(::toProcessRow) }
    }

    private fun toProcessRow(item: Any): ProcessRow? {
        var pid = 0
        var uid = 0
        var name: String? = null
        for (f in R.allFields(item.javaClass)) {
            val lower = f.name.lowercase()
            when {
                lower == "pid" || lower.endsWith("mPid") || lower == "mpid" ->
                    pid = R.asInt(R.readField(item, f), pid)
                lower == "uid" || lower.endsWith("mUid") || lower == "muid" ->
                    uid = R.asInt(R.readField(item, f), uid)
                lower.contains("processname") || lower.contains("procname") ->
                    name = R.readField(item, f) as? String ?: name
            }
        }
        val resolved = name ?: return null
        return ProcessRow(pid = pid, uid = uid, name = resolved)
    }

    // ---------------------------------------------------------------- whitelist predicates

    fun isTempWhite(pkg: String): Boolean? {
        if (refs.m("isTempWhite") == null) return null
        return R.asBool(call("isTempWhite", pkg))
    }

    fun isNoRestrictApp(pkg: String): Boolean? {
        if (refs.m("isNoRestrictApp") == null) return null
        return R.asBool(call("isNoRestrictApp", pkg))
    }

    fun isAllowWakeUpList(pkg: String): Boolean? {
        if (refs.m("isAllowWakeUpList") == null) return null
        return R.asBool(call("isAllowWakeUpList", pkg))
    }

    // ---------------------------------------------------------------- resources

    fun isJobRestrict(uid: Int): Boolean? =
        if (refs.m("isJobRestrict") == null) null else R.asBool(call("isJobRestrict", uid))

    fun setJobRestrict(uid: Int, restrict: Boolean): Boolean =
        call("jobRestrict", uid, restrict) != null || call("jobRestrict", uid) != null

    fun setWakeLockLimit(uid: Int, limit: Boolean): Boolean =
        call("updateWakeLockLimit", uid, limit) != null

    fun setUdpRestrict(uid: Int, restrict: Boolean): Boolean =
        call("udpPackageRestrict", uid, restrict) != null

    fun isFreezeBinderEnabled(): Boolean? =
        if (refs.m("getFreezeBinderEnable") == null) null else R.asBool(call("getFreezeBinderEnable"))

    // ---------------------------------------------------------------- modes

    fun triggerQuickFreezeMode(mode: String, uid: Int, reason: String): Boolean =
        call("triggerQuickFreezeMode", mode, uid, reason) != null

    fun listFieldSnapshot(key: String): List<String> {
        val f = refs.f(key) ?: return emptyList()
        val value = R.readField(resolveInstance(), f) ?: return emptyList()
        return when (value) {
            is Collection<*> -> value.filterIsInstance<String>()
            is Array<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
    }

    fun patchListField(key: String, extra: Collection<String>, remove: Collection<String> = emptyList()): Boolean {
        val f = refs.f(key) ?: return false
        val target = resolveInstance() ?: return false
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
        } catch (_: Throwable) {
            false
        }
    }
}
