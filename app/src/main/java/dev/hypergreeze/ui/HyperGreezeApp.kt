package dev.hypergreeze.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.hypergreeze.core.Command
import dev.hypergreeze.ui.components.HgIcons
import dev.hypergreeze.ui.components.MiuixBottomSheet
import dev.hypergreeze.ui.components.MiuixSnackbar
import dev.hypergreeze.ui.components.MiuixTabBar
import dev.hypergreeze.ui.pages.AppDetailPage
import dev.hypergreeze.ui.pages.AppsPage
import dev.hypergreeze.ui.pages.DiagnosticsPage
import dev.hypergreeze.ui.pages.EventsPage
import dev.hypergreeze.ui.pages.HomePage
import dev.hypergreeze.ui.pages.RevertSheetContent
import dev.hypergreeze.ui.theme.HyperGreezeTheme
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.vm.MainViewModel
import kotlinx.coroutines.delay

private val TABS = listOf(
    "首页" to HgIcons.Home,
    "应用" to HgIcons.Apps,
    "事件" to HgIcons.Events,
    "诊断" to HgIcons.Diagnostics,
)

@Composable
fun HyperGreezeApp(vm: MainViewModel) {
    HyperGreezeTheme {
        val state by vm.state.collectAsState()
        val context = LocalContext.current
        var tab by rememberSaveable { mutableStateOf(0) }
        var detailPkg by rememberSaveable { mutableStateOf<String?>(null) }
        var sheet by rememberSaveable { mutableStateOf(0) }
        var message by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(vm) {
            vm.messages.collect { message = it }
        }
        LaunchedEffect(message) {
            if (message != null) {
                delay(2_400L)
                message = null
            }
        }

        val openWriteSettings = {
            try {
                val intent: Intent = vm.writeSettingsIntent()
                context.startActivity(intent)
            } catch (_: Throwable) {
                vm.requestWriteSettingsHint()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colors.background),
        ) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Box(modifier = Modifier.weight(1f)) {
                    val pkg = detailPkg
                    when {
                        pkg != null -> AppDetailPage(
                            pkg = pkg,
                            state = state,
                            vm = vm,
                            onBack = { detailPkg = null },
                        )

                        tab == 0 -> HomePage(
                            state = state,
                            onOpenWriteSettings = openWriteSettings,
                            onToggleEnabled = { value -> vm.setGlobal { g -> g.copy(enabled = value) } },
                            onToggleForceBg = { value ->
                                vm.setGlobal { g -> g.copy(forceFreezeOnBackground = value) }
                            },
                            onToggleAudit = { value -> vm.setGlobal { g -> g.copy(audit = value) } },
                            onTogglePatchLists = { value ->
                                vm.setGlobal { g -> g.copy(patchLists = value) }
                            },
                            onThawAll = { vm.thawEverything() },
                            onQuickMode = { mode -> vm.send(Command.OP_QUICK_MODE, arg = mode) },
                            onClearSafeMode = { vm.clearSafeMode() },
                            onRequestSafeMode = { vm.requestSafeMode() },
                            onOpenManager = {
                                val intent = context.packageManager
                                    .getLaunchIntentForPackage("org.lsposed.manager")
                                if (intent != null) {
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Throwable) {
                                        vm.notifyManagerMissing()
                                    }
                                } else {
                                    vm.notifyManagerMissing()
                                }
                            },
                            onOpenRevert = { sheet = 1 },
                            onOpenClearData = { sheet = 2 },
                        )

                        tab == 1 -> AppsPage(
                            state = state,
                            onOpen = { detailPkg = it },
                        )

                        tab == 2 -> EventsPage(
                            state = state,
                            onClear = { vm.clearEvents() },
                        )

                        else -> DiagnosticsPage(
                            state = state,
                            onSelfTest = { vm.selfTest() },
                            report = { vm.diagnosticsReport() },
                            onRefresh = { vm.refreshAll() },
                            onRequestSafeMode = { vm.requestSafeMode() },
                            onClearSafeMode = { vm.clearSafeMode() },
                            onApplyLists = { vm.applyLists() },
                            onToggleFilterPolarity = { value ->
                                vm.setGlobal { g -> g.copy(filterPolarityInverted = value) }
                            },
                        )
                    }
                }
                if (detailPkg == null) {
                    MiuixTabBar(
                        tabs = TABS,
                        selectedIndex = tab,
                        onSelect = { tab = it },
                    )
                }
            }

            val snack = message
            if (snack != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (detailPkg == null) 80.dp else 24.dp),
                ) {
                    MiuixSnackbar(snack)
                }
            }

            MiuixBottomSheet(
                visible = sheet != 0,
                title = if (sheet == 2) "彻底清理" else "还原模块改动",
                onDismiss = { sheet = 0 },
            ) {
                RevertSheetContent(
                    state = state,
                    clearData = sheet == 2,
                    onConfirm = {
                        val clearing = sheet == 2
                        sheet = 0
                        // Pause the module locally as well: the hook side latches `suppressed`, but
                        // an explicit "off" is what the user asked for by pressing revert.
                        vm.setGlobal { g -> g.copy(enabled = false) }
                        if (clearing) vm.clearAllData() else vm.revertAll()
                    },
                    onClose = { sheet = 0 },
                )
            }
        }

        BackHandler(enabled = detailPkg != null) { detailPkg = null }
    }
}
