package dev.hypergreeze.ui.vm

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hypergreeze.core.AppPolicy
import dev.hypergreeze.core.Command
import dev.hypergreeze.core.EventRecord
import dev.hypergreeze.core.FreezeMode
import dev.hypergreeze.core.FreezeState
import dev.hypergreeze.core.GlobalPolicy
import dev.hypergreeze.core.Heartbeat
import dev.hypergreeze.core.Policy
import dev.hypergreeze.core.SceneBit
import dev.hypergreeze.ui.data.AppEntry
import dev.hypergreeze.ui.data.AppInventory
import dev.hypergreeze.ui.data.SettingsGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = true,
    val policy: Policy = Policy.EMPTY,
    val heartbeat: Heartbeat? = null,
    val events: List<EventRecord> = emptyList(),
    val logs: List<String> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val canWriteSettings: Boolean = false,
    val heartbeatAgeMs: Long = Long.MAX_VALUE,
    val frozenKnown: Int = 0,
) {
    val hookAlive: Boolean get() = heartbeat != null && heartbeatAgeMs < 120_000L
    val safeMode: Boolean get() = heartbeat?.safeMode == true
    val configuredCount: Int get() = policy.apps.count { it.value.isEffective }
    val neverCount: Int get() = policy.apps.count { it.value.mode == FreezeMode.NEVER }
    val immediateCount: Int get() = policy.apps.count { it.value.mode == FreezeMode.IMMEDIATE }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val gateway = SettingsGateway(application)
    private val inventory = AppInventory(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = runCatching { inventory.load() }.getOrDefault(emptyList())
            _state.update { it.copy(apps = apps, loading = false) }
        }
        refreshAll()
    }

    fun onStart() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshTelemetry()
                delay(2_500L)
            }
        }
        refreshAll()
    }

    fun onStop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshAll() {
        val canWrite = gateway.canWrite()
        val policy = gateway.readPolicy()
        _state.update {
            it.copy(policy = policy, canWriteSettings = canWrite)
        }
        refreshTelemetry()
    }

    private fun refreshTelemetry() {
        val hb = gateway.readHeartbeat()
        val events = gateway.readEvents()
        val logs = gateway.readLogs()
        val age = hb?.ts?.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
        _state.update {
            it.copy(
                heartbeat = hb,
                events = events,
                logs = logs,
                heartbeatAgeMs = age,
                frozenKnown = hb?.hooks?.sumOf { hook -> hook.hits }?.toInt() ?: 0,
            )
        }
    }

    private fun notify(message: String) {
        _messages.tryEmit(message)
    }

    // ------------------------------------------------------------------ policy writes

    private fun persist(policy: Policy) {
        if (!gateway.canWrite()) {
            notify("缺少「修改系统设置」权限，策略未保存")
            return
        }
        if (gateway.writePolicy(policy)) {
            _state.update { it.copy(policy = policy, canWriteSettings = true) }
        } else {
            notify("写入 Settings.System 被拒绝，请在诊断页做写入自检")
        }
    }

    fun setGlobal(transform: (GlobalPolicy) -> GlobalPolicy) {
        val current = _state.value.policy
        persist(current.withGlobal(transform(current.global)))
    }

    fun setAppMode(pkg: String, mode: FreezeMode) {
        val current = _state.value.policy
        val app = current.appOrDefault(pkg)
        persist(current.withApp(pkg, app.copy(mode = mode)))
    }

    fun updateApp(pkg: String, transform: (AppPolicy) -> AppPolicy) {
        val current = _state.value.policy
        val app = current.appOrDefault(pkg)
        val next = transform(app)
        val locked = SceneBit.toMask(SceneBit.LOCKED_DROP)
        persist(current.withApp(pkg, next.copy(maskDrop = next.maskDrop and locked.inv())))
    }

    fun resetApp(pkg: String) {
        val current = _state.value.policy
        persist(current.withoutApp(pkg))
    }

    fun toggleKeepBit(pkg: String, bit: SceneBit) {
        val current = _state.value.policy
        val app = current.appOrDefault(pkg)
        val keep = app.keepBits.toMutableSet()
        val drop = app.dropBits.toMutableSet()
        if (bit in keep) keep.remove(bit) else {
            keep.add(bit)
            drop.remove(bit)
        }
        persist(current.withApp(pkg, app.withMask(keep, drop)))
    }

    fun toggleDropBit(pkg: String, bit: SceneBit) {
        val current = _state.value.policy
        val app = current.appOrDefault(pkg)
        val keep = app.keepBits.toMutableSet()
        val drop = app.dropBits.toMutableSet()
        if (bit in drop) drop.remove(bit) else {
            drop.add(bit)
            keep.remove(bit)
        }
        persist(current.withApp(pkg, app.withMask(keep, drop)))
    }

    fun setProcessMode(pkg: String, process: String, mode: FreezeMode) {
        val current = _state.value.policy
        val app = current.appOrDefault(pkg)
        val processes = app.processes.toMutableMap()
        if (mode == FreezeMode.FOLLOW) processes.remove(process) else processes[process] = mode
        persist(current.withApp(pkg, app.copy(processes = processes)))
    }

    fun setResource(pkg: String, key: String, value: Boolean?) {
        val current = _state.value.policy
        val app = current.appOrDefault(pkg)
        val res = app.resources
        val next = when (key) {
            "net" -> res.copy(net = value)
            "alarm" -> res.copy(alarm = value)
            "job" -> res.copy(job = value)
            "wakelock" -> res.copy(wakelock = value)
            "broadcast" -> res.copy(broadcast = value)
            "cpu" -> res.copy(cpu = value)
            else -> res
        }
        persist(current.withApp(pkg, app.copy(resources = next)))
    }

    fun setThaw(pkg: String, key: String, value: Boolean?) {
        val current = _state.value.policy
        val app = current.appOrDefault(pkg)
        val thaw = app.thawOn
        val next = when (key) {
            "screenOn" -> thaw.copy(screenOn = value)
            "netChange" -> thaw.copy(netChange = value)
            "notification" -> thaw.copy(notification = value)
            "audioFocus" -> thaw.copy(audioFocus = value)
            "charging" -> thaw.copy(charging = value)
            "externalWake" -> thaw.copy(externalWake = value)
            else -> thaw
        }
        persist(current.withApp(pkg, app.copy(thawOn = next)))
    }

    // ------------------------------------------------------------------ commands

    fun send(op: String, pkg: String? = null, uid: Int = 0, arg: String? = null) {
        if (!gateway.canWrite()) {
            notify("缺少「修改系统设置」权限，命令未发送")
            return
        }
        val command = Command(seq = gateway.currentSeq(), op = op, pkg = pkg, uid = uid, arg = arg)
        if (gateway.sendCommand(command)) {
            notify("指令已发送：$op")
        } else {
            notify("指令写入失败")
        }
    }

    fun writeSettingsIntent(): Intent = gateway.writePermissionIntent()

    fun selfTest(): String = gateway.selfTest()

    fun requestWriteSettingsHint() {
        notify("请手动到 系统设置 → 应用 → HyperGreeze → 修改系统设置 授权")
    }

    fun notifyManagerMissing() {
        notify("未找到 LSPosed 管理器；请手动打开并在「模块」里启用 HyperGreeze")
    }

    fun thawEverything() = send(Command.OP_THAW_ALL)

    fun freezeNow(pkg: String) = send(Command.OP_FREEZE_PKG, pkg = pkg)

    fun thawUid(uid: Int) = send(Command.OP_THAW_UID, uid = uid)

    fun triggerQuickMode(mode: String) = send(Command.OP_QUICK_MODE, arg = mode)

    fun clearEvents() = send(Command.OP_CLEAR_EVENTS)

    fun clearSafeMode() = send(Command.OP_CLEAR_SAFE_MODE)

    fun applyLists() = send(Command.OP_APPLY_LISTS)

    fun setFreezeBinder(enabled: Boolean) = send(Command.OP_SET_BINDER, arg = if (enabled) "1" else "0")

    fun requestSafeMode() {
        if (!gateway.canWrite()) {
            notify("缺少「修改系统设置」权限")
            return
        }
        if (gateway.requestSafeMode("用户从界面请求")) {
            notify("已请求安全模式：下次开机 hook 不接管")
        } else {
            notify("safe-mode 请求写入失败")
        }
    }

    // ------------------------------------------------------------------ derived helpers

    fun appsWithPolicy(): List<AppEntry> {
        val configured = _state.value.policy.apps.keys
        return _state.value.apps.filter { it.pkg in configured }
    }

    fun appEntry(pkg: String): AppEntry? = _state.value.apps.firstOrNull { it.pkg == pkg }

    /**
     * The hook side deliberately does not publish per-uid cgroup state — that would mean one
     * Binder round trip per uid on every poll. So the UI only claims what it can prove: an app in
     * NEVER is never frozen, everything else is "unknown" rather than a fabricated badge.
     */
    fun freezeStateOf(pkg: String): FreezeState =
        when (_state.value.policy.app(pkg)?.mode) {
            FreezeMode.NEVER -> FreezeState.UNFROZEN
            FreezeMode.IMMEDIATE, FreezeMode.CUSTOM -> FreezeState.UNKNOWN
            else -> FreezeState.UNKNOWN
        }

    fun diagnosticsReport(): String = buildString {
        val s = _state.value
        appendLine("# HyperGreeze 诊断报告")
        appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("模块: ${s.heartbeat?.moduleVersion ?: "未知"}")
        appendLine("框架: ${s.heartbeat?.frameworkName ?: "?"} ${s.heartbeat?.frameworkVersion ?: ""} api=${s.heartbeat?.apiVersion ?: 0}")
        appendLine("进程: ${s.heartbeat?.processName ?: "?"}")
        appendLine("构建: ${s.heartbeat?.buildFingerprint ?: "?"}")
        appendLine("GreeZe: present=${s.heartbeat?.greezePresent} class=${s.heartbeat?.greezeClass} instance=${s.heartbeat?.instanceOk}")
        appendLine("策略: rev=${s.policy.revision} apps=${s.policy.apps.size} enabled=${s.policy.global.enabled}")
        appendLine("安全模式: ${s.heartbeat?.safeMode} reason=${s.heartbeat?.safeModeReason} guard=${s.heartbeat?.bootGuard}")
        appendLine("钩子:")
        s.heartbeat?.hooks?.forEach { hook ->
            appendLine("  - ${hook.id} [${hook.state}] hits=${hook.hits} errors=${hook.errors} ${hook.note ?: ""}")
        }
        appendLine("能力:")
        s.heartbeat?.capabilities?.forEach { (key, value) -> appendLine("  - $key=$value") }
        appendLine("事件(最近 ${s.events.size}):")
        s.events.take(30).forEach { appendLine("  - ${it.short()}") }
        appendLine("日志(最近 ${s.logs.size}):")
        s.logs.takeLast(40).forEach { appendLine("  - $it") }
    }
}
