package dev.hypergreeze.module.greeze

import dev.hypergreeze.module.Logx
import dev.hypergreeze.module.R
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Everything the hook layer needs in order to reach GreeZe, resolved once per boot.
 *
 * Two deliberate design choices:
 *
 *  - **Name + arity resolution, never a hard-coded descriptor.** An OTA that turns
 *    `freezeUid(int, long, int, String, int)` into a 4-arg variant still yields a usable hook, and
 *    a key that disappears is reported as `missing` instead of throwing.
 *  - **No `getInstance()`.** The bytecode shows `getInstance()` is a double-checked-locking
 *    "create if null" factory; calling it before GreeZe's real init would build a zombie service
 *    that is not the one bound to `ServiceManager`. The instance is captured from the constructor
 *    and from `init(Context)` instead, with the `sInstance` static field as a read-only fallback.
 */
class GreezeRefs(
    val managerClass: Class<*>,
    val filterClass: Class<*>?,
    val immobulusClass: Class<*>?,
    val listAppsClass: Class<*>?,
    val freezeStateClass: Class<*>?,
    val foregroundClass: Class<*>?,
    val methods: Map<String, Method>,
    val fields: Map<String, Field>,
    val notes: List<String>,
) {
    fun m(key: String): Method? = methods[key]
    fun f(key: String): Field? = fields[key]

    fun capabilities(): Map<String, Boolean> = linkedMapOf(
        "manager" to true,
        "filter" to (filterClass != null),
        "immobulus" to (immobulusClass != null),
        "listApps" to (listAppsClass != null),
        "freezeUid" to methods.containsKey("freezeUid"),
        "freezePids" to methods.containsKey("freezePids"),
        "freezeAction" to methods.containsKey("freezeAction"),
        "thawUid" to methods.containsKey("thawUid"),
        "thawAll" to (methods.containsKey("thawAll3") || methods.containsKey("thawAll1")),
        "freezeState" to methods.containsKey("getFreezeState"),
        "frozenUids" to methods.containsKey("getFrozenUids"),
        "processList" to methods.containsKey("getProcessByUid"),
        "packagesOfUid" to methods.containsKey("getPackageNamesFromUid"),
        "tempWhite" to methods.containsKey("isTempWhite"),
        "foreground" to (foregroundClass != null && methods.containsKey("onForegroundActivitiesChanged")),
        "quickFreezeMode" to methods.containsKey("triggerQuickFreezeMode"),
        "checkReason" to methods.containsKey("getAurogonCheckReason"),
        "resources" to (
            methods.containsKey("jobRestrict") ||
                methods.containsKey("updateWakeLockLimit") ||
                methods.containsKey("udpPackageRestrict") ||
                methods.containsKey("isRestrictReceiver")
            ),
    )
}

internal enum class Where { MANAGER, IMMOBULUS, FILTER, FOREGROUND }

internal data class MethodSpec(
    val key: String,
    val names: Array<String>,
    val arity: Int?,
    val where: Where = Where.MANAGER,
)

internal data class FieldSpec(val key: String, val names: Array<String>, val where: Where = Where.MANAGER)

object GreezeProbe {

    private const val BASE = "com.miui.server.greeze."

    private val MANAGER_CANDIDATES = arrayOf(
        BASE + "GreezeManagerService",
        BASE + "GreezeManager",
        BASE + "GreezeService",
    )

    private val FILTER_CANDIDATES = arrayOf(BASE + "AurogonFilterManager", BASE + "AurogonFilter")

    private val IMMOBULUS_CANDIDATES = arrayOf(BASE + "AurogonImmobulusMode", BASE + "ImmobulusMode")

    private val LIST_APPS_CANDIDATES = arrayOf(
        BASE + "power.ListAppsManager", // confirmed: com.miui.server.greeze.power.ListAppsManager
        BASE + "ListAppsManager",
        BASE + "power.AppListManager",
    )

    private val FREEZE_STATE_CANDIDATES = arrayOf(BASE + "FreezeState", BASE + "GreezeFreezeState")

    /**
     * Arities copied from the recovered bytecode contract (docs/HOOK_MAP.md cites the source).
     * `null` = name-only lookup, used where the number of parameters genuinely varies by build.
     */
    private val METHOD_SPECS = listOf(
        // ---- freeze primitives ----
        MethodSpec("freezeAction", arrayOf("freezeAction"), 5),
        MethodSpec("freezeUid", arrayOf("freezeUid"), 5),
        MethodSpec("freezeUids", arrayOf("freezeUids"), 5),
        MethodSpec("freezePids", arrayOf("freezePids"), 4),
        MethodSpec("freezeProcess", arrayOf("freezeProcess"), 4),
        MethodSpec("freezeThread", arrayOf("freezeThread"), 1),
        MethodSpec("freezeFloatWins", arrayOf("freezeFloatWins"), 2),
        MethodSpec("freezeBinder", arrayOf("freezeBinder"), 4),
        MethodSpec("checkAndFreezeIsolated", arrayOf("checkAndFreezeIsolated"), 3),
        // ---- thaw primitives ----
        MethodSpec("thawUid", arrayOf("thawUid"), 3),
        MethodSpec("thawUidAsync", arrayOf("thawUidAsync"), 3),
        MethodSpec("thawUids", arrayOf("thawUids"), 3),
        MethodSpec("thawPids", arrayOf("thawPids"), 3),
        MethodSpec("thawProcess", arrayOf("thawProcess"), 3),
        MethodSpec("thawThread", arrayOf("thawThread"), 1),
        MethodSpec("thawAll3", arrayOf("thawAll"), 3),
        MethodSpec("thawAll1", arrayOf("thawAll"), 1),
        MethodSpec("thawuidsAll", arrayOf("thawuidsAll"), 1),
        MethodSpec("thawUidsByType", arrayOf("thawUidsByType"), 1),
        MethodSpec("resetCgroupUidStatus", arrayOf("resetCgroupUidStatus"), 1),
        MethodSpec("resetCgroupUidFreezeStatus", arrayOf("resetCgroupUidFreezeStatus"), 0),
        // ---- state ----
        MethodSpec("getFreezeState", arrayOf("getFreezeState"), 1),
        MethodSpec("isUidFrozen", arrayOf("isUidFrozen"), 1),
        MethodSpec("isUidPartiallyFrozen", arrayOf("isUidPartiallyFrozen"), 1),
        MethodSpec("isUidLive", arrayOf("isUidLive"), 1),
        MethodSpec("isUidActive", arrayOf("isUidActive"), 1),
        MethodSpec("getFrozenUids", arrayOf("getFrozenUids"), 1),
        MethodSpec("getFrozenPids", arrayOf("getFrozenPids"), 1),
        MethodSpec("getLastThawedTime", arrayOf("getLastThawedTime"), 2),
        MethodSpec("getProcessByUid", arrayOf("getProcessByUid"), 1),
        MethodSpec("getPackageNamesFromUid", arrayOf("getPackageNamesFromUid"), 1),
        MethodSpec("getParentUidForIsolated", arrayOf("getParentUidForIsolated"), 1),
        MethodSpec("getAppFrozenEventList", arrayOf("getAppFrozenEventList"), 0),
        MethodSpec("getAurogonCheckReason", arrayOf("getAurogonCheckReason"), 1),
        // ---- whitelist predicates ----
        MethodSpec("isTempWhite", arrayOf("isTempWhite", "isTempWhiteList"), 1),
        MethodSpec("isNoRestrictApp", arrayOf("isNoRestrictApp"), 1, Where.IMMOBULUS),
        MethodSpec("isAllowWakeUpList", arrayOf("isAllowWakeUpList", "isAllowWakeupList"), 1, Where.IMMOBULUS),
        // ---- resources ----
        MethodSpec("isJobRestrict", arrayOf("isJobRestrict"), 1),
        MethodSpec("jobRestrict", arrayOf("jobRestrict"), 1),
        MethodSpec("isWakeLockLimit", arrayOf("isWakeLockLimit"), 1),
        MethodSpec("updateWakeLockLimit", arrayOf("updateWakeLockLimit"), 2),
        MethodSpec("udpPackageRestrict", arrayOf("udpPackageRestrict"), 2),
        MethodSpec("isRestrictReceiver", arrayOf("isRestrictReceiver"), 5),
        MethodSpec("isRestrictBackgroundAction", arrayOf("isRestrictBackgroundAction"), 5),
        MethodSpec("checkCpuLimitEnable", arrayOf("checkCpuLimitEnable"), 0),
        MethodSpec("getFreezeBinderEnable", arrayOf("getFreezeBinderEnable"), 0),
        MethodSpec("setFreezeBinderEnableCloud", arrayOf("setFreezeBinderEnableCloud"), 1),
        // ---- modes ----
        MethodSpec("triggerQuickFreezeMode", arrayOf("triggerQuickFreezeMode"), 3),
        MethodSpec("triggerLaunchMode", arrayOf("triggerLaunchMode"), 2),
        MethodSpec("triggerIdleAppAction", arrayOf("triggerIdleAppAction"), 1),
        MethodSpec("triggerGMSLimitAction", arrayOf("triggerGMSLimitAction"), 0),
        MethodSpec("needThawWhenScreenOn", arrayOf("needThawWhenScreenOn"), 1),
        MethodSpec("setLimitActionForDelayScheduled", arrayOf("setLimitActionForDelayScheduled"), 2),
        // ---- lifecycle / instance (recorded but never called blindly) ----
        MethodSpec("init", arrayOf("init"), 1),
        MethodSpec("getService", arrayOf("getService"), 0),
        MethodSpec("getInstance", arrayOf("getInstance"), 0),
    )

    private val FIELD_SPECS = listOf(
        FieldSpec("sInstance", arrayOf("sInstance")),
        FieldSpec("IGreezeManagerSingleton", arrayOf("IGreezeManagerSingleton")),
        FieldSpec("mFrozenPids", arrayOf("mFrozenPids")),
        FieldSpec("mManageredMap", arrayOf("mManageredMap")),
        FieldSpec("mBootCompleted", arrayOf("mBootCompleted")),
        FieldSpec("mImmobulusMode", arrayOf("mImmobulusMode")),
        FieldSpec("mAurogonAlarmAllowList", arrayOf("mAurogonAlarmAllowList")),
        FieldSpec("mImmobulusModeWhiteList", arrayOf("mImmobulusModeWhiteList")),
        FieldSpec("mFgWhiteImmoMode", arrayOf("mFgWhiteImmoMode")),
        FieldSpec("mNoRestrictAppSet", arrayOf("mNoRestrictAppSet"), Where.IMMOBULUS),
        FieldSpec("mAllowWakeUpPackageNameList", arrayOf("mAllowWakeUpPackageNameList"), Where.IMMOBULUS),
        FieldSpec("mMessageApp", arrayOf("mMessageApp"), Where.IMMOBULUS),
        FieldSpec("mGrabTicketsAppList", arrayOf("mGrabTicketsAppList"), Where.IMMOBULUS),
        FieldSpec("mVideoAppList", arrayOf("mVideoAppList"), Where.IMMOBULUS),
        FieldSpec("mVPNAppList", arrayOf("mVPNAppList"), Where.IMMOBULUS),
        FieldSpec("mMutiWindowsApp", arrayOf("mMutiWindowsApp"), Where.IMMOBULUS),
        FieldSpec("mContinueReason", arrayOf("mContinueReason"), Where.FILTER),
    )

    fun discover(classLoader: ClassLoader?): GreezeRefs? {
        val manager = R.classesOrNull(classLoader, *MANAGER_CANDIDATES)
        if (manager == null) {
            Logx.w("GreeZe not present: none of ${MANAGER_CANDIDATES.joinToString()} resolves")
            return null
        }
        val filter = R.classesOrNull(classLoader, *FILTER_CANDIDATES)
        val immobulus = R.classesOrNull(classLoader, *IMMOBULUS_CANDIDATES)
        val listApps = R.classesOrNull(classLoader, *LIST_APPS_CANDIDATES)
        val freezeState = R.classesOrNull(classLoader, *FREEZE_STATE_CANDIDATES)
        val notes = ArrayList<String>()

        val methods = LinkedHashMap<String, Method>()
        for (spec in METHOD_SPECS) {
            val target = when (spec.where) {
                Where.MANAGER -> manager
                Where.IMMOBULUS -> immobulus
                Where.FILTER -> filter
                Where.FOREGROUND -> null
            } ?: continue
            val found = R.anyMethod(target, arity = spec.arity, names = *spec.names)
            if (found != null) {
                methods[spec.key] = found
            } else if (spec.where == Where.MANAGER) {
                notes.add("${spec.key} 未找到")
            }
        }

        // onForegroundActivitiesChanged lives on an anonymous inner class (GreezeManagerService$13),
        // never on the service itself: scan the declared classes for the 3-arg callback.
        var foregroundClass: Class<*>? = null
        val foreground = findForegroundCallback(manager)?.also { (cls, method) ->
            foregroundClass = cls
            methods["onForegroundActivitiesChanged"] = method
            notes.add("foreground callback on ${cls.name}")
        }?.second
        if (foreground == null) notes.add("onForegroundActivitiesChanged 未找到")

        val fields = LinkedHashMap<String, Field>()
        for (spec in FIELD_SPECS) {
            val target = when (spec.where) {
                Where.MANAGER -> manager
                Where.IMMOBULUS -> immobulus
                Where.FILTER -> filter
                Where.FOREGROUND -> null
            } ?: continue
            val found = R.field(target, *spec.names)
                ?: R.field(immobulus, *spec.names)
                ?: R.field(manager, *spec.names)
                ?: R.field(listApps, *spec.names)
            if (found != null) fields[spec.key] = found
        }

        notes.add(
            "manager=${manager.simpleName} filter=${filter?.simpleName} " +
                "immobulus=${immobulus?.simpleName} listApps=${listApps?.simpleName} " +
                "methods=${methods.size} fields=${fields.size}",
        )
        Logx.i("GreeZe probe: ${notes.joinToString("; ")}")

        return GreezeRefs(
            managerClass = manager,
            filterClass = filter,
            immobulusClass = immobulus,
            listAppsClass = listApps,
            freezeStateClass = freezeState,
            foregroundClass = foregroundClass,
            methods = methods,
            fields = fields,
            notes = notes,
        )
    }

    private fun findForegroundCallback(manager: Class<*>): Pair<Class<*>, Method>? {
        val candidates = ArrayList<Class<*>>()
        candidates.add(manager)
        try {
            candidates.addAll(manager.declaredClasses)
        } catch (_: Throwable) {
            // ignore
        }
        for (cls in candidates) {
            val method = R.method(cls, "onForegroundActivitiesChanged", 3) ?: continue
            return cls to method
        }
        return null
    }
}
