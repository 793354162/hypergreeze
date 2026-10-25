package dev.hypergreeze.module

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import dev.hypergreeze.core.Command
import dev.hypergreeze.core.CommandCodec
import dev.hypergreeze.core.Channel
import dev.hypergreeze.core.EventType
import dev.hypergreeze.core.FreezeMode
import dev.hypergreeze.core.Heartbeat
import dev.hypergreeze.core.HookState
import dev.hypergreeze.core.Keys
import dev.hypergreeze.core.Module
import dev.hypergreeze.core.Policy
import dev.hypergreeze.core.TelemetryCodec
import dev.hypergreeze.module.greeze.GreezeFacade
import dev.hypergreeze.module.greeze.GreezeProbe
import dev.hypergreeze.module.greeze.GreezeRefs
import io.github.libxposed.api.XposedModule

/**
 * Owns the whole hook-side lifecycle.
 *
 * Start-up is staged on purpose, because `onSystemServerStarting` runs on the boot thread and
 * everything we do there delays the phone:
 *
 *  1. probe GreeZe classes (cheap, reflective only);
 *  2. evaluate the boot-loop guard (system properties only -no `Context` needed yet);
 *  3. install hooks; they are harmless while no policy is loaded, because an empty policy means
 *     "every app follows the system";
 *  4. asynchronously acquire a `Context`, then start the policy store, telemetry and the command
 *     channel (with retries: `getSystemContext()` is not always ready at step 1).
 */
class ModuleController(
    private val module: XposedModule,
    private val classLoader: ClassLoader?,
    private val moduleVersion: String,
) {

    private val main = Handler(Looper.getMainLooper())
    private val emptyEngine = PolicyEngine(Policy.EMPTY)

    private var worker: HandlerThread? = null
    private var work: Handler? = null
    private var refs: GreezeRefs? = null
    private var facade: GreezeFacade? = null
    private var effector: Effector? = null
    private var installer: HookInstaller? = null
    private var telemetry: ModuleTelemetry? = null
    private var store: PolicyStore? = null
    private var guard: SafetyGuard? = null
    private var appContext: Context? = null

    private var contextAttempts = 0
    private var started = false
    private var lastCommandSeq = 0L
    private var commandObserver: ContentObserver? = null
    private var lastRevision = -1L
    private var tickCount = 0L

    fun start() {
        if (started) return
        started = true

        val discovered = GreezeProbe.discover(classLoader)
        if (discovered == null) {
            Logx.w("GreeZe classes not found on this ROM; HyperGreeze stays inert")
            return
        }
        refs = discovered

        val greeze = GreezeFacade(discovered)
        facade = greeze

        // Every mutation of GreeZe state goes through the effector so it lands in the ledger.
        val eff = Effector(greeze, { telemetry }, { store?.policy?.revision ?: 0L })
        effector = eff

        val safety = SafetyGuard()
        guard = safety
        val decision = safety.begin()

        val thread = HandlerThread("HyperGreeze").apply { start() }
        worker = thread
        val workerHandler = Handler(thread.looper)
        work = workerHandler

        val hooks = HookInstaller(
            module = module,
            refs = discovered,
            facade = greeze,
            engineProvider = { store?.engine ?: emptyEngine },
            work = workerHandler,
        )
        installer = hooks
        hooks.effector = eff
        hooks.passive = decision.passive
        hooks.onGreezeReady = { main.post { ensureContext() } }
        val statuses = hooks.install()
        Logx.i(
            "hooks installed=${statuses.count { it.state == HookState.INSTALLED }}/${statuses.size} " +
                "passive=${decision.passive} guard=${decision.bootGuard}",
        )

        main.post { ensureContext() }
        main.postDelayed({
            safety.markStable()
            publishHeartbeat()
        }, Module.STABLE_WINDOW_MS)
        scheduleTick()
    }

    // ------------------------------------------------------------------ context / wiring

    private fun ensureContext() {
        if (appContext != null) return
        val ctx = ContextResolver.resolve(facade)
        if (ctx == null) {
            contextAttempts++
            if (contextAttempts <= MAX_CONTEXT_ATTEMPTS) {
                main.postDelayed({ ensureContext() }, CONTEXT_RETRY_MS)
            } else {
                Logx.w("no Context after $contextAttempts attempts; policy + telemetry stay off")
            }
            return
        }
        appContext = ctx
        Logx.i("context acquired after $contextAttempts attempts")

        val tm = ModuleTelemetry(ctx)
        telemetry = tm
        tm.loadExistingEvents()
        tm.loadExistingJournal()
        installer?.telemetry = tm

        // Restore the command watermark from the previous generation's heartbeat so an old command
        // is never replayed after a reboot.
        TelemetryCodec.decodeHeartbeat(tm.readString(Keys.HEARTBEAT))?.let { hb ->
            lastCommandSeq = maxOf(lastCommandSeq, hb.lastCommandSeq)
            Logx.i("restored heartbeat: lastCommandSeq=$lastCommandSeq revision=${hb.revision}")
        }

        val policyStore = PolicyStore(ctx)
        store = policyStore
        policyStore.start()
        registerObservers(ctx)
        consumeSafeModeRequest(tm)
        consumeGuardReset(tm)

        // A provider query can start our app process, so the first read happens on the worker.
        work?.post {
            policyStore.reload(allowProvider = true)
            lastRevision = policyStore.policy.revision
            applyGlobalSideEffects(policyStore.engine)
            tm.record(
                EventType.BOOT,
                result = "boot",
                detail = "hooks=${installer?.snapshot()?.count { it.state == HookState.INSTALLED } ?: 0}" +
                    " channel=${policyStore.lastChannel}",
            )
            publishHeartbeat()
            processCommand()
        }
    }

    /**
     * Global switches that have a real GreeZe-side effect. All three are opt-in (`null` / `false`
     * by default) so a stock install never touches a system-wide switch on its own.
     */
    private fun applyGlobalSideEffects(engine: PolicyEngine) {
        val global = engine.policy.global
        telemetry?.eventCap = if (global.compactEvents) COMPACT_EVENT_CAP else Module.EVENT_RING
        // A revert latches `suppressed`; any policy edit releases it.
        effector?.onPolicyRevision(engine.policy.revision)
        global.applyFreezeBinder?.let { desired ->
            work?.post { effector?.freezeBinder(desired) }
        }
        if (global.patchLists) work?.post { applyLists() }
    }

    /**
     * The UI writes a request flag instead of a `persist.sys.*` property (it cannot write those).
     * It is consumed here — a few seconds into boot — and latches the property, so the request
     * takes effect from the **next** boot, which is exactly what the UI promises.
     */
    private fun consumeSafeModeRequest(tm: ModuleTelemetry) {
        if (!tm.readFlag(Keys.SAFE_MODE_REQUEST)) return
        val reason = tm.readString(Keys.SAFE_MODE_REASON).orEmpty().ifBlank { "用户从界面请求" }
        SysProp.setInt(SafetyGuard.PROP_SAFE, 1)
        SysProp.set(SafetyGuard.PROP_SAFE_REASON, reason)
        tm.writeFlag(Keys.SAFE_MODE_REQUEST, false)
        Logx.w("safe-mode request consumed ($reason); next boot stays passive")
    }

    /**
     * `adb shell settings put system greeze_ctl_boot_guard_v1 0` (or the diagnostics page) clears
     * the boot-loop counter without needing `setprop`. The authoritative state stays in
     * `persist.sys.hypergreeze.guard`; this key is only a one-shot request, deleted once consumed.
     */
    private fun consumeGuardReset(tm: ModuleTelemetry) {
        if (tm.readString(Keys.BOOT_GUARD)?.trim() != "0") return
        SysProp.setInt(SafetyGuard.PROP_GUARD, 0)
        tm.writeString(Keys.BOOT_GUARD, null)
        Logx.i("boot guard counter cleared by Settings request")
    }

    private fun registerObservers(ctx: Context) {
        if (commandObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                work?.post {
                    processCommand()
                    // Policy changes also arrive through here when the provider notifies.
                    store?.let { policyStore ->
                        if (policyStore.reload(allowProvider = true)) {
                            installer?.invalidateUidCache()
                            applyGlobalSideEffects(policyStore.engine)
                        }
                    }
                    publishHeartbeat()
                }
            }
        }
        commandObserver = observer
        for (uri in listOf(
            Keys.URI_POLICY,
            Keys.URI_COMMAND,
            Settings.System.getUriFor(Keys.COMMAND).toString(),
            Settings.System.getUriFor(Keys.POLICY).toString(),
        )) {
            try {
                ctx.contentResolver.registerContentObserver(Uri.parse(uri), true, observer)
            } catch (t: Throwable) {
                Logx.w("observer registration failed for $uri", t)
            }
        }
    }

    // ------------------------------------------------------------------ periodic work

    private fun scheduleTick() {
        main.postDelayed(
            object : Runnable {
                override fun run() {
                    try {
                        tick()
                    } catch (t: Throwable) {
                        Logx.w("tick failed", t)
                    }
                    main.postDelayed(this, TICK_MS)
                }
            },
            TICK_MS,
        )
    }

    private fun tick() {
        val policyStore = store ?: return
        tickCount++
        // Settings-only on the periodic path: reading the provider may start the app process.
        if (policyStore.reload(allowProvider = false)) {
            installer?.invalidateUidCache()
            telemetry?.record(
                EventType.INFO,
                result = "policy-reload",
                detail = "rev=${policyStore.policy.revision}",
            )
        }
        if (policyStore.policy.revision != lastRevision) {
            lastRevision = policyStore.policy.revision
            applyGlobalSideEffects(policyStore.engine)
            publishHeartbeat()
        }
        // Dirty-guarded: writing nothing when nothing changed, so a 5s cadence costs one lock.
        work?.post {
            telemetry?.flushEvents()
            telemetry?.flushLogs()
            telemetry?.flushJournal()
        }
        if (tickCount % HEARTBEAT_EVERY_TICKS == 0L) publishHeartbeat()
    }

    private fun publishHeartbeat() {
        val tm = telemetry ?: return
        val hooks = installer ?: return
        val engine = store?.engine ?: emptyEngine
        val hb = Heartbeat(
            ts = System.currentTimeMillis(),
            moduleVersion = moduleVersion,
            apiVersion = module.apiVersion,
            frameworkName = module.frameworkName,
            frameworkVersion = module.frameworkVersion,
            frameworkProperties = module.frameworkProperties,
            processName = "system_server",
            buildFingerprint = android.os.Build.FINGERPRINT ?: "",
            greezePresent = true,
            greezeClass = refs?.managerClass?.name,
            instanceOk = facade?.hasInstance == true,
            policyEnabled = engine.enabled,
            safeMode = hooks.passive,
            safeModeReason = guard?.safeModeReason(),
            bootGuard = guard?.decision?.bootGuard ?: 0,
            revision = engine.policy.revision,
            lastCommandSeq = lastCommandSeq,
            journalUndoable = tm.journalUndoable(),
            suppressed = effector?.suppressed == true,
            channel = store?.lastChannel ?: Channel.NONE,
            hooks = hooks.snapshot(),
            capabilities = refs?.capabilities() ?: emptyMap(),
        )
        tm.publishHeartbeat(hb, force = true)
    }

    // ------------------------------------------------------------------ commands

    private fun processCommand() {
        val tm = telemetry ?: return
        val raw = store?.readCommand() ?: tm.readString(Keys.COMMAND) ?: return
        val command = CommandCodec.decode(raw) ?: return
        if (command.seq <= lastCommandSeq) return
        lastCommandSeq = command.seq
        val result = try {
            execute(command)
        } catch (t: Throwable) {
            Logx.w("command ${command.op} failed", t)
            "error: ${t.javaClass.simpleName}"
        }
        Logx.i("command ${command.op} seq=${command.seq} -> $result")
        tm.record(
            EventType.INFO,
            uid = command.uid,
            pkg = command.pkg,
            result = result,
            detail = "cmd:${command.op}",
        )
        tm.flushEvents()
        publishHeartbeat()
    }

    private fun execute(command: Command): String {
        val greeze = facade ?: return "no-greeze"
        val eff = effector
        val tm = telemetry
        return when (command.op) {
            Command.OP_THAW_ALL, Command.OP_RESET_CGROUP -> {
                val ok = eff?.thawAll() ?: greeze.thawAll()
                tm?.record(EventType.THAW, result = if (ok) "ok" else "fail", detail = command.op)
                if (ok) "ok" else "fail"
            }

            Command.OP_THAW_UID -> {
                val ok = eff?.thaw(command.uid, command.pkg) ?: greeze.thawUid(command.uid)
                tm?.record(EventType.THAW, command.uid, command.pkg, result = if (ok) "ok" else "fail")
                if (ok) "ok" else "fail"
            }

            Command.OP_FREEZE_UID -> {
                if (eff != null) {
                    eff.freeze(command.uid, command.pkg, Module.REASON_MOD) ?: "fail"
                } else {
                    greeze.freezeUid(command.uid, 0L, Module.REASON_MOD, Module.FREEZE_TAG, 0) ?: "fail"
                }
            }

            Command.OP_FREEZE_PKG -> {
                val uid = uidOf(command.pkg)
                when {
                    uid <= 0 -> "no-uid"
                    eff != null -> eff.freeze(uid, command.pkg, Module.REASON_MOD) ?: "fail"
                    else -> greeze.freezeUid(uid, 0L, Module.REASON_MOD, Module.FREEZE_TAG, 0) ?: "fail"
                }
            }

            Command.OP_QUICK_MODE -> {
                val mode = command.arg ?: "quick_freeze"
                if (eff != null) {
                    eff.quickMode(mode, command.uid)
                    "ok"
                } else if (greeze.triggerQuickFreezeMode(mode, command.uid, "hypergreeze")) {
                    "ok"
                } else {
                    "fail"
                }
            }

            Command.OP_CLEAR_EVENTS -> {
                telemetry?.clearEvents()
                "ok"
            }

            Command.OP_CLEAR_SAFE_MODE -> {
                guard?.clearSafeMode()
                installer?.passive = false
                "ok"
            }

            Command.OP_SET_BINDER -> {
                val enabled = command.arg == "1"
                if (eff != null) {
                    eff.freezeBinder(enabled)
                    "ok"
                } else if (greeze.setFreezeBinderEnabled(enabled)) {
                    "ok"
                } else {
                    "fail"
                }
            }

            Command.OP_APPLY_LISTS -> applyLists()

            Command.OP_REVERT_ALL -> eff?.revertAll() ?: "no-effector"

            Command.OP_CLEAR_DATA -> {
                val reverted = eff?.revertAll() ?: "no-effector"
                "$reverted；${clearAllData()}"
            }

            else -> "unknown-op"
        }
    }

    private fun applyLists(): String {
        val engine = store?.engine ?: return "no-policy"
        val eff = effector ?: return "no-effector"
        val never = engine.policy.apps.filter { it.value.mode == FreezeMode.NEVER }.keys
        val immediate = engine.policy.apps.filter { it.value.mode == FreezeMode.IMMEDIATE }.keys
        var ok = false
        if (never.isNotEmpty()) ok = eff.patchList("mNoRestrictAppSet", never) || ok
        if (immediate.isNotEmpty()) ok = eff.patchList("mGrabTicketsAppList", immediate) || ok
        return if (ok) "ok(never=${never.size},immediate=${immediate.size})" else "fail"
    }

    /**
     * Deletes every Settings key and system property this module owns. Intended for "I am about to
     * uninstall": hooks die with the process and in-memory list patches vanish, but resource
     * restrictions GreeZe already applied and our own keys would otherwise survive.
     */
    private fun clearAllData(): String {
        val tm = telemetry ?: return "no-telemetry"
        var deleted = 0
        for (key in Keys.ALL) {
            if (tm.writeString(key, null)) deleted++
        }
        SysProp.setInt(SafetyGuard.PROP_GUARD, 0)
        SysProp.setInt(SafetyGuard.PROP_SAFE, 0)
        SysProp.set(SafetyGuard.PROP_SAFE_REASON, "")
        // Without this the very next heartbeat would recreate our keys a few seconds later.
        tm.silenced = true
        Logx.w("cleared $deleted settings keys + hypergreeze properties; telemetry silenced")
        return "已清除 $deleted 个设置键与全部模块属性"
    }

    private fun uidOf(pkg: String?): Int {
        if (pkg.isNullOrBlank()) return -1
        val ctx = appContext ?: return -1
        return try {
            ctx.packageManager.getPackageUid(pkg, 0)
        } catch (t: Throwable) {
            -1
        }
    }

    fun stop() {
        installer?.unhookAll()
        store?.stop()
        commandObserver?.let {
            try {
                appContext?.contentResolver?.unregisterContentObserver(it)
            } catch (_: Throwable) {
                // ignore
            }
        }
        commandObserver = null
        worker?.quitSafely()
        worker = null
        started = false
    }

    private companion object {
        const val TICK_MS = 5_000L
        const val HEARTBEAT_EVERY_TICKS = 6L
        const val CONTEXT_RETRY_MS = 1_500L
        const val MAX_CONTEXT_ATTEMPTS = 20
        const val COMPACT_EVENT_CAP = 20
    }
}
