package dev.hypergreeze.module.greeze

import dev.hypergreeze.module.Logx
import dev.hypergreeze.module.R
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Everything the hook layer needs to reach GreeZe, resolved once.
 *
 * Deliberately *not* hard-coded descriptors: each logical key is resolved by name (plus a
 * preferred arity) against the classes actually loaded in this system_server, and a missing key is
 * recorded instead of aborting. That keeps the module alive across HyperOS OTA changes (the report's
 * §5 "版本漂移" risk) and lets the diagnostics page show exactly what was found on *this* build.
 */
class GreezeRefs(
    val managerClass: Class<*>,
    val filterClass: Class<*>?,
    val immobulusClass: Class<*>?,
    val listAppsClass: Class<*>?,
    val policyMakerClass: Class<*>?,
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
        "policyMaker" to (policyMakerClass != null),
        "freezeUid" to methods.containsKey("freezeUid"),
        "freezePids" to methods.containsKey("freezePids"),
        "freezeAction" to methods.containsKey("freezeAction"),
        "thawUid" to methods.containsKey("thawUid"),
        "thawAll" to (methods.containsKey("thawAll") || methods.containsKey("thawuidsAll")),
        "freezeState" to methods.containsKey("getFreezeState"),
        "frozenUids" to methods.containsKey("getFrozenUids"),
        "processList" to methods.containsKey("getProcessByUid"),
        "packagesOfUid" to methods.containsKey("getPackageNamesFromUid"),
        "tempWhite" to methods.containsKey("isTempWhite"),
        "foregroundCb" to methods.containsKey("onForegroundActivitiesChanged"),
        "quickFreezeMode" to methods.containsKey("triggerQuickFreezeMode"),
        "resources" to (
            methods.containsKey("jobRestrict") ||
                methods.containsKey("updateWakeLockLimit") ||
                methods.containsKey("udpPackageRestrict")
            ),
    )
}

internal data class MethodSpec(val key: String, val names: Array<String>, val arity: Int?)

internal data class FieldSpec(val key: String, val names: Array<String>)

object GreezeProbe {

    private const val PKG = "com.miui.server.greeze."

    private val MANAGER_CANDIDATES = arrayOf(
        PKG + "GreezeManagerService",
        PKG + "GreezeManager",
        PKG + "GreezeService",
    )

    private val FILTER_CANDIDATES = arrayOf(
        PKG + "AurogonFilterManager",
        PKG + "AurogonFilter",
    )

    private val IMMOBULUS_CANDIDATES = arrayOf(
        PKG + "AurogonImmobulusMode",
        PKG + "ImmobulusMode",
    )

    private val LIST_APPS_CANDIDATES = arrayOf(
        PKG + "ListAppsManager",
        PKG + "AppListManager",
    )

    private val POLICY_MAKER_CANDIDATES = arrayOf(
        PKG + "PolicyMaker",
        PKG + "policy.PolicyMaker",
    )

    /**
     * Logical key -> candidate Java names (+ preferred arity). Arities follow the signatures in
     * the analysis report; when none matches, [R.method] falls back to the unique name match.
     */
    private val METHOD_SPECS = listOf(
        // freeze
        MethodSpec("freezeUid", arrayOf("freezeUid"), 5),
        MethodSpec("freezeUids", arrayOf("freezeUids"), 5),
        MethodSpec("freezePids", arrayOf("freezePids"), 4),
        MethodSpec("freezeProcess", arrayOf("freezeProcess"), 4),
        MethodSpec("freezeAction", arrayOf("freezeAction"), 5),
        MethodSpec("freezeFloatWins", arrayOf("freezeFloatWins"), 2),
        MethodSpec("freezeBinder", arrayOf("freezeBinder"), 4),
        MethodSpec("checkAndFreezeIsolated", arrayOf("checkAndFreezeIsolated"), 3),
        // thaw
        MethodSpec("thawUid", arrayOf("thawUid"), 3),
        MethodSpec("thawUidAsync", arrayOf("thawUidAsync"), 3),
        MethodSpec("thawUids", arrayOf("thawUids"), 3),
        MethodSpec("thawPids", arrayOf("thawPids"), 3),
        MethodSpec("thawProcess", arrayOf("thawProcess"), 3),
        MethodSpec("unFreezeFloatWins", arrayOf("unFreezeFloatWins"), 2),
        MethodSpec("thawAll", arrayOf("thawAll"), 2),
        MethodSpec("thawuidsAll", arrayOf("thawuidsAll"), 1),
        MethodSpec("thawUidsByType", arrayOf("thawUidsByType"), 1),
        MethodSpec("resetCgroupUidStatus", arrayOf("resetCgroupUidStatus"), 1),
        // state
        MethodSpec("getFreezeState", arrayOf("getFreezeState"), 1),
        MethodSpec("isUidFrozen", arrayOf("isUidFrozen"), 1),
        MethodSpec("isUidPartiallyFrozen", arrayOf("isUidPartiallyFrozen"), 1),
        MethodSpec("isUidLive", arrayOf("isUidLive"), 1),
        MethodSpec("isUidActive", arrayOf("isUidActive"), 1),
        MethodSpec("getFrozenUids", arrayOf("getFrozenUids"), 1),
        MethodSpec("getLastThawedTime", arrayOf("getLastThawedTime"), 2),
        MethodSpec("getProcessByUid", arrayOf("getProcessByUid"), 1),
        MethodSpec("getPackageNamesFromUid", arrayOf("getPackageNamesFromUid"), 1),
        MethodSpec("getParentUidForIsolated", arrayOf("getParentUidForIsolated"), 1),
        MethodSpec("getAppFrozenEventList", arrayOf("getAppFrozenEventList"), 0),
        // whitelist
        MethodSpec("isTempWhite", arrayOf("isTempWhite", "isTempWhiteList"), 1),
        MethodSpec("isNoRestrictApp", arrayOf("isNoRestrictApp"), 1),
        MethodSpec("isAllowWakeUpList", arrayOf("isAllowWakeUpList", "isAllowWakeupList"), 1),
        MethodSpec("isLaunchWhiteListApp", arrayOf("isLaunchWhiteListApp"), 1),
        // foreground callback
        MethodSpec("onForegroundActivitiesChanged", arrayOf("onForegroundActivitiesChanged"), 3),
        // resources
        MethodSpec("isJobRestrict", arrayOf("isJobRestrict"), 1),
        MethodSpec("jobRestrict", arrayOf("jobRestrict"), 1),
        MethodSpec("isWakeLockLimit", arrayOf("isWakeLockLimit"), 1),
        MethodSpec("updateWakeLockLimit", arrayOf("updateWakeLockLimit"), 2),
        MethodSpec("udpPackageRestrict", arrayOf("udpPackageRestrict"), 2),
        MethodSpec("isRestrictReceiver", arrayOf("isRestrictReceiver"), 5),
        MethodSpec("isRestrictBackgroundAction", arrayOf("isRestrictBackgroundAction"), 5),
        MethodSpec("getFreezeBinderEnable", arrayOf("getFreezeBinderEnable"), 0),
        MethodSpec("setFreezeBinderEnableCloud", arrayOf("setFreezeBinderEnableCloud"), 1),
        MethodSpec("checkCpuLimitEnable", arrayOf("checkCpuLimitEnable"), 0),
        // modes
        MethodSpec("triggerQuickFreezeMode", arrayOf("triggerQuickFreezeMode"), 3),
        MethodSpec("setLimitActionForDelayScheduled", arrayOf("setLimitActionForDelayScheduled"), 2),
        MethodSpec("needThawWhenScreenOn", arrayOf("needThawWhenScreenOn"), 1),
        // init / instance
        MethodSpec("getInstance", arrayOf("getInstance", "getService", "getGreezeManagerService"), 0),
        MethodSpec("init", arrayOf("init"), null),
    )

    private val FIELD_SPECS = listOf(
        FieldSpec("mNoRestrictAppSet", arrayOf("mNoRestrictAppSet", "mNoRestrictApps")),
        FieldSpec("mAllowWakeUpPackageNameList", arrayOf("mAllowWakeUpPackageNameList")),
        FieldSpec("mMessageApp", arrayOf("mMessageApp")),
        FieldSpec("mAurogonAlarmAllowList", arrayOf("mAurogonAlarmAllowList")),
        FieldSpec("mImmobulusModeWhiteList", arrayOf("mImmobulusModeWhiteList")),
        FieldSpec("mFgWhiteImmoMode", arrayOf("mFgWhiteImmoMode")),
        FieldSpec("mFrozenPids", arrayOf("mFrozenPids")),
        FieldSpec("mMutiWindowsApp", arrayOf("mMutiWindowsApp")),
        FieldSpec("mGrabTicketsAppList", arrayOf("mGrabTicketsAppList")),
        FieldSpec("mVideoAppList", arrayOf("mVideoAppList")),
        FieldSpec("mVPNAppList", arrayOf("mVPNAppList")),
        FieldSpec("mContext", arrayOf("mContext", "mSystemContext")),
    )

    fun discover(classLoader: ClassLoader?): GreezeRefs? {
        val manager = R.classesOrNull(classLoader, *MANAGER_CANDIDATES)
        if (manager == null) {
            Logx.w("GreeZe not present: none of ${MANAGER_CANDIDATES.joinToString()} resolves")
            return null
        }
        val notes = ArrayList<String>()
        val methods = LinkedHashMap<String, Method>()
        for (spec in METHOD_SPECS) {
            val target = when (spec.key) {
                "isNoRestrictApp", "isAllowWakeUpList" -> null // resolved on the immobulus class below
                else -> manager
            }
            if (target == null) continue
            val found = R.anyMethod(target, arity = spec.arity, names = *spec.names)
            if (found != null) {
                methods[spec.key] = found
            }
        }

        val filter = R.classesOrNull(classLoader, *FILTER_CANDIDATES)
        val immobulus = R.classesOrNull(classLoader, *IMMOBULUS_CANDIDATES)
        val listApps = R.classesOrNull(classLoader, *LIST_APPS_CANDIDATES)
        val policyMaker = R.classesOrNull(classLoader, *POLICY_MAKER_CANDIDATES)

        // `isNoRestrictApp` / `isAllowWakeUpList` live on AurogonImmobulusMode; accept either home.
        for (spec in METHOD_SPECS) {
            if (spec.key != "isNoRestrictApp" && spec.key != "isAllowWakeUpList") continue
            val found = R.anyMethod(immobulus, arity = 1, names = *spec.names)
                ?: R.anyMethod(manager, arity = 1, names = *spec.names)
            if (found != null) methods[spec.key] = found else notes.add("${spec.key} 未找到")
        }

        val fields = LinkedHashMap<String, Field>()
        for (spec in FIELD_SPECS) {
            val found = R.field(immobulus, *spec.names)
                ?: R.field(manager, *spec.names)
                ?: R.field(listApps, *spec.names)
            if (found != null) fields[spec.key] = found
        }

        for (spec in METHOD_SPECS) {
            if (spec.key in methods) continue
            if (spec.key == "isNoRestrictApp" || spec.key == "isAllowWakeUpList") continue
            notes.add("${spec.key} 未找到")
        }
        notes.add("manager=${manager.name} methods=${methods.size} fields=${fields.size}")
        Logx.i("GreeZe probe: manager=${manager.name} filter=${filter?.name} immobulus=${immobulus?.name} " +
            "listApps=${listApps?.name} methods=${methods.size}")

        return GreezeRefs(
            managerClass = manager,
            filterClass = filter,
            immobulusClass = immobulus,
            listAppsClass = listApps,
            policyMakerClass = policyMaker,
            methods = methods,
            fields = fields,
            notes = notes,
        )
    }

    fun managerClassLoaderHint(): String = MANAGER_CANDIDATES.first()
}
