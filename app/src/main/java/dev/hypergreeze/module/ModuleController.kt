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
        lastRevision = policyStore.policy.revision

        consumeSafeModeRequest(tm)
        registerObservers(ctx)
        publishHeartbeat()
        work?.post { processCommand() }
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

    private fun registerObservers(ctx: Context) {
        if (commandObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                work?.post { processCommand() }
            }
        }
        commandObserver = observer
        try {
            ctx.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Keys.COMMAND),
                false,
                observer,
            )
            ctx.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Keys.POLICY),
                false,
                observer,
            )
        } catch (t: Throwable) {
            Logx.w("observer registration failed", t)
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
        if (policyStore.reload()) {
            installer?.invalidateUidCache()
            telemetry?.record(
                EventType.INFO,
                result = "policy-reload",
                detail = "rev=${policyStore.policy.revision}",
            )
        }
        if (policyStore.policy.revision != lastRevision) {
            lastRevision = policyStore.policy.revision
            publishHeartbeat()
        }
        // Dirty-guarded: writing nothing when nothing changed, so a 5s cadence costs one lock.
        work?.post {
            telemetry?.flushEvents()
            telemetry?.flushLogs()
        }
        if (tickCount % HEARTBEAT_EVERY_TICKS == 0) publishHeartbeat()
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
            hooks = hooks.snapshot(),
            capabilities = refs?.capabilities() ?: emptyMap(),
        )
        tm.publishHeartbeat(hb, force = true)
    }

    // ------------------------------------------------------------------ commands

    private fun processCommand() {
        val tm = telemetry ?: return
        val raw = tm.readString(Keys.COMMAND) ?: return
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
        return when (command.op) {
            Command.OP_THAW_ALL, Command.OP_RESET_CGROUP ->
                if (greeze.thawAll()) "ok" else "fail"

            Command.OP_THAW_UID ->
                if (greeze.thawUid(command.uid)) "ok" else "fail"

            Command.OP_FREEZE_UID ->
                greeze.freezeUid(command.uid, 0L, Module.REASON_MOD, Module.FREEZE_TAG, 0) ?: "fail"

            Command.OP_FREEZE_PKG -> {
                val uid = uidOf(command.pkg)
                if (uid <= 0) {
                    "no-uid"
                } else {
                    greeze.freezeUid(uid, 0L, Module.REASON_MOD, Module.FREEZE_TAG, 0) ?: "fail"
                }
            }

            Command.OP_QUICK_MODE -> {
                val mode = command.arg ?: "quick_freeze"
                if (greeze.triggerQuickFreezeMode(mode, command.uid, "hypergreeze")) "ok" else "fail"
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
                if (greeze.setFreezeBinderEnabled(enabled)) "ok" else "fail"
            }

            Command.OP_APPLY_LISTS -> applyLists()

            else -> "unknown-op"
        }
    }

    private fun applyLists(): String {
        val engine = store?.engine ?: return "no-policy"
        val greeze = facade ?: return "no-greeze"
        val never = engine.policy.apps.filter { it.value.mode == FreezeMode.NEVER }.keys
        val immediate = engine.policy.apps.filter { it.value.mode == FreezeMode.IMMEDIATE }.keys
        var ok = false
        if (never.isNotEmpty()) ok = greeze.patchListField("mNoRestrictAppSet", never) || ok
        if (immediate.isNotEmpty()) ok = greeze.patchListField("mGrabTicketsAppList", immediate) || ok
        return if (ok) "ok(never=${never.size},immediate=${immediate.size})" else "fail"
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
    }
}
