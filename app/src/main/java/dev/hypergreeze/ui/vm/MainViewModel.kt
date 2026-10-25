package dev.hypergreeze.ui.vm

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import dev.hypergreeze.core.AppPolicy
import dev.hypergreeze.core.Command
import dev.hypergreeze.core.EventRecord
import dev.hypergreeze.core.FreezeMode
import dev.hypergreeze.core.FreezeState
import dev.hypergreeze.core.GlobalPolicy
import dev.hypergreeze.core.Heartbeat
import dev.hypergreeze.core.Keys
import dev.hypergreeze.core.Policy
import dev.hypergreeze.core.SceneBit
import dev.hypergreeze.core.TelemetryCodec
import dev.hypergreeze.ui.data.AppEntry
import dev.hypergreeze.ui.data.AppInventory
import dev.hypergreeze.ui.data.SettingsGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    val journal: List<dev.hypergreeze.core.JournalEntry> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val canWriteSettings: Boolean = false,
    val heartbeatAgeMs: Long = Long.MAX_VALUE,
) {
    val hookAlive: Boolean get() = heartbeat != null && heartbeatAgeMs < 120_000L
    val safeMode: Boolean get() = heartbeat?.safeMode == true
    val configuredCount: Int get() = policy.apps.count { it.value.isEffective }
    val neverCount: Int get() = policy.apps.count { it.value.mode == FreezeMode.NEVER }
    val immediateCount: Int get() = policy.apps.count { it.value.mode == FreezeMode.IMMEDIATE }

    /** Entries the hook side can actually undo right now. */
    val undoable: List<dev.hypergreeze.core.JournalEntry>
        get() = journal.filter { it.ok && it.op.reversible }

    /** Entries it knowingly cannot undo (void setters, mode triggers). */
    val irreversible: List<dev.hypergreeze.core.JournalEntry>
        get() = journal.filter { it.ok && !it.op.reversible }

    val expertMode: Boolean get() = policy.global.uiExpert
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val gateway = SettingsGateway(application)
    private val inventory = AppInventory(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var pollJob: Job? = null
    private var lastEventsRaw: String? = null
    private var lastLogsRaw: String? = null
    private var lastJournalRaw: String? = null

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
        viewModelScope.launch { refreshAllNow() }
    }

    private suspend fun refreshAllNow() {
        val canWrite = gateway.canWrite()
        val policy = gateway.readPolicy()
        _state.update {
            it.copy(policy = policy, canWriteSettings = canWrite)
        }
        refreshTelemetry()
    }

    private suspend fun refreshTelemetry() {
        val hb = gateway.readHeartbeat()
        // Compare the raw strings first: decoding 60 events + 120 log lines + 200 journal entries
        // every 2.5s is wasted work when nothing changed (which is the normal case). When something
        // did change, the decode happens on Default instead of the main thread.
        val eventsRaw = gateway.readRaw(Keys.EVENTS)
        val logsRaw = gateway.readRaw(Keys.LOGS)
        val journalRaw = gateway.readRaw(Keys.JOURNAL)
        val current = _state.value
        val events = if (eventsRaw == lastEventsRaw) {
            current.events
        } else {
            withContext(Dispatchers.Default) { TelemetryCodec.decodeEvents(eventsRaw).asReversed() }
        }
        val logs = if (logsRaw == lastLogsRaw) {
            current.logs
        } else {
            withContext(Dispatchers.Default) { TelemetryCodec.decodeLogs(logsRaw) }
        }
        val journal = if (journalRaw == lastJournalRaw) {
            current.journal
        } else {
            withContext(Dispatchers.Default) {
                dev.hypergreeze.core.JournalCodec.decode(journalRaw).asReversed()
            }
        }
        lastEventsRaw = eventsRaw
        lastLogsRaw = logsRaw
        lastJournalRaw = journalRaw
        val age = hb?.ts?.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
        _state.update {
            it.copy(
                heartbeat = hb,
                events = events,
                logs = logs,
                journal = journal,
                heartbeatAgeMs = age,
            )
        }
    }

    private fun notify(message: String) {
        _messages.tryEmit(message)
    }

    // ------------------------------------------------------------------ policy writes

    private fun persist(policy: Policy) {
        // No WRITE_SETTINGS gate: the primary channel is our own prefs + provider. The Settings
        // mirror inside writePolicy() is best effort and may be refused by the ROM.
        if (gateway.writePolicy(policy)) {
            _state.update { it.copy(policy = policy) }
        } else {
            notify("策略写入失败：应用存储不可写")
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

    fun notifyExportFailed() {
        notify("报告写入失败，检查应用存储空间")
    }

    fun thawEverything() = send(Command.OP_THAW_ALL)

    fun freezeNow(pkg: String) = send(Command.OP_FREEZE_PKG, pkg = pkg)

    fun thawUid(uid: Int) = send(Command.OP_THAW_UID, uid = uid)

    fun triggerQuickMode(mode: String) = send(Command.OP_QUICK_MODE, arg = mode)

    fun clearEvents() = send(Command.OP_CLEAR_EVENTS)

    fun clearSafeMode() = send(Command.OP_CLEAR_SAFE_MODE)

    fun applyLists() = send(Command.OP_APPLY_LISTS)

    /** Undo every journaled side effect, thaw everything, stop re-applying. Policy is kept. */
    fun revertAll() = send(Command.OP_REVERT_ALL)

    /** Revert + delete every Settings key and system property this module owns (for uninstall). */
    fun clearAllData() = send(Command.OP_CLEAR_DATA)

    fun setFreezeBinder(enabled: Boolean) = send(Command.OP_SET_BINDER, arg = if (enabled) "1" else "0")

    fun requestSafeMode() {
        // This one really does need WRITE_SETTINGS: the hook side reads the request from Settings.
        if (!gateway.canWrite()) {
            notify("安全模式请求需要「修改系统设置」权限")
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

    fun diagnosticsReport(): String {
        val s = _state.value
        val hb = s.heartbeat
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        return buildString {
            appendLine("# HyperGreeze 诊断报告")
            appendLine("time=$time")
            appendLine("module=${hb?.moduleVersion ?: "?"}")
            appendLine("framework=${hb?.frameworkName ?: "?"} ${hb?.frameworkVersion ?: ""} api=${hb?.apiVersion ?: 0}")
            appendLine("process=${hb?.processName ?: "?"}")
            appendLine("build=${hb?.buildFingerprint ?: "?"}")
            appendLine("channel=${hb?.channel ?: "none"}")
            appendLine("writeSettings=${if (s.canWriteSettings) "granted" else "denied"} (仅镜像通道)")
            appendLine("greeze present=${hb?.greezePresent} class=${hb?.greezeClass} instance=${hb?.instanceOk}")
            appendLine("policy rev=${s.policy.revision} apps=${s.policy.apps.size} enabled=${s.policy.global.enabled} expert=${s.policy.global.uiExpert}")
            appendLine("safeMode=${hb?.safeMode} reason=${hb?.safeModeReason ?: "-"} guard=${hb?.bootGuard ?: 0}")
            appendLine("suppressed=${hb?.suppressed} journalUndoable=${hb?.journalUndoable ?: 0}")
            appendLine("heartbeatAgeMs=${s.heartbeatAgeMs}")

            appendLine()
            appendLine("## hooks")
            hb?.hooks?.forEach { hook ->
                appendLine("${hook.id}\t${hook.state}\thits=${hook.hits}\terrors=${hook.errors}\t${hook.note ?: ""}")
            }

            appendLine()
            appendLine("## caps")
            appendLine(hb?.capabilities?.entries?.joinToString(" ") { "${it.key}=${it.value}" } ?: "-")

            appendLine()
            appendLine("## events (newest first, max 15)")
            s.events.take(15).forEach { appendLine("${formatStamp(it.ts)}\t${it.short()}") }

            appendLine()
            appendLine("## journal (newest first, max 15)")
            s.journal.take(15).forEach { appendLine("${formatStamp(it.ts)}\t${it.oneLine}") }

            appendLine()
            appendLine("## logs (max 15)")
            s.logs.takeLast(15).forEach { appendLine(it) }
        }
    }

    private fun formatStamp(ts: Long): String =
        if (ts <= 0) "--:--:--"
        else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))

    /**
     * Writes the report next to the app's external files and returns the file, so the caller can
     * hand it to a share sheet. Filesystem export instead of the clipboard: the report is far
     * larger than a comfortable paste.
     */
    fun exportReport(): File? {
        val app = getApplication<Application>()
        val base = try {
            app.getExternalFilesDir(null) ?: app.filesDir
        } catch (_: Throwable) {
            app.filesDir
        }
        val dir = File(base, "reports")
        if (!dir.exists() && !dir.mkdirs()) return null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "hypergreeze-$stamp.txt")
        return try {
            file.writeText(diagnosticsReport())
            file
        } catch (_: Throwable) {
            null
        }
    }

    fun shareReportIntent(file: File): Intent {
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "dev.hypergreeze.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "HyperGreeze 诊断报告")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
