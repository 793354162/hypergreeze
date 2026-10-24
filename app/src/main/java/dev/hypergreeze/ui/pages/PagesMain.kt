package dev.hypergreeze.ui.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.hypergreeze.core.EventType
import dev.hypergreeze.core.Explain
import dev.hypergreeze.core.HookState
import dev.hypergreeze.ui.components.ButtonStyle
import dev.hypergreeze.ui.components.HgIcons
import dev.hypergreeze.ui.components.MiuixBadge
import dev.hypergreeze.ui.components.MiuixButton
import dev.hypergreeze.ui.components.MiuixCard
import dev.hypergreeze.ui.components.MiuixChip
import dev.hypergreeze.ui.components.MiuixDivider
import dev.hypergreeze.ui.components.MiuixEmptyState
import dev.hypergreeze.ui.components.MiuixNoticeBar
import dev.hypergreeze.ui.components.MiuixRow
import dev.hypergreeze.ui.components.MiuixSwitch
import dev.hypergreeze.ui.components.MiuixText
import dev.hypergreeze.ui.components.NoticeTone
import dev.hypergreeze.ui.components.PageScaffold
import dev.hypergreeze.ui.theme.Dimens
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.theme.MiuixType
import dev.hypergreeze.ui.vm.UiState

private val MODE_TOKENS = listOf("quick_freeze", "immobulus", "launch_mode", "grab_tickets", "video")

@Composable
fun HomePage(
    state: UiState,
    onOpenWriteSettings: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleForceBg: (Boolean) -> Unit,
    onToggleAudit: (Boolean) -> Unit,
    onTogglePatchLists: (Boolean) -> Unit,
    onThawAll: () -> Unit,
    onQuickMode: (String) -> Unit,
    onClearSafeMode: () -> Unit,
    onRequestSafeMode: () -> Unit,
    onOpenManager: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val hb = state.heartbeat
    val statusText = when {
        hb == null -> "等待 Hook 心跳"
        state.safeMode -> "安全模式（被动）"
        !state.hookAlive -> "心跳过期"
        else -> "正常运行"
    }
    val statusColor = when {
        hb == null || !state.hookAlive -> colors.textHint
        state.safeMode -> colors.warning
        else -> colors.success
    }

    PageScaffold(
        title = "HyperGreeze",
        subtitle = "逐 App 冻结控制 · libxposed API 102",
    ) {
        item(key = "summary") {
            Block {
                Gap(4)
                MiuixCard {
                    Column(modifier = Modifier.padding(14.dp)) {
                        MiuixText(
                            "当前效果",
                            style = MiuixType.smallSectionTitle,
                            color = colors.textSecondary,
                        )
                        Gap(4)
                        MiuixText(
                            Explain.summary(state.policy),
                            style = MiuixType.caption,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }

        item(key = "status") {
            Block {
                Gap(4)
                MiuixCard {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        StatRow(
                            Triple("钩子", "${hb?.installedHooks ?: 0}/${hb?.totalHooks ?: 0}", colors.primary),
                            Triple("已配置", "${state.configuredCount}", colors.warning),
                            Triple("永不冻结", "${state.neverCount}", colors.success),
                            Triple("事件", "${state.events.size}", colors.textSecondary),
                        )
                        MiuixDivider()
                        MiuixRow(
                            title = "Hook 状态",
                            value = statusText,
                            valueColor = statusColor,
                            subtitle = hb?.let { "${it.frameworkName} ${it.frameworkVersion} · api ${it.apiVersion}" },
                        )
                        MiuixDivider()
                        MiuixRow(
                            title = "GreeZe 实例",
                            value = when {
                                hb == null -> "未知"
                                hb.instanceOk -> "已捕获"
                                hb.greezePresent -> "存在但未捕获"
                                else -> "不存在"
                            },
                            subtitle = hb?.greezeClass,
                        )
                        MiuixDivider()
                        MiuixRow(
                            title = "心跳",
                            value = when {
                                hb == null -> "无"
                                state.heartbeatAgeMs < 5_000 -> "刚刚"
                                else -> "${state.heartbeatAgeMs / 1000}s 前"
                            },
                        )
                    }
                }
            }
        }

        if (!state.canWriteSettings) {
            item(key = "perm") {
                Gap()
                Block {
                    MiuixNoticeBar(
                        text = "未授予「修改系统设置」权限，策略只读。",
                        tone = NoticeTone.WARNING,
                        actionLabel = "去授权",
                        onAction = onOpenWriteSettings,
                    )
                }
            }
        }

        if (state.safeMode) {
            item(key = "safemode") {
                Gap()
                Block {
                    MiuixNoticeBar(
                        text = "安全模式已生效：所有判定放行（${hb?.safeModeReason ?: "原因未知"}）。",
                        tone = NoticeTone.DANGER,
                        actionLabel = "解除",
                        onAction = onClearSafeMode,
                    )
                }
            }
        }

        if (hb != null && !hb.greezePresent) {
            item(key = "nogreeze") {
                Gap()
                Block {
                    MiuixNoticeBar(
                        text = "本机未找到 com.miui.server.greeze.*，模块不会做任何事。",
                        tone = NoticeTone.WARNING,
                    )
                }
            }
        }

        item(key = "onboarding") {
            if (hb == null) {
                Gap()
                Block {
                    MiuixCard {
                        MiuixRow(
                            title = "启用步骤",
                            subtitle = "LSPosed 启用（作用域只勾「系统框架」）→ 授予「修改系统设置」→ 软重启 system_server",
                            onClick = onOpenManager,
                            value = "打开 LSPosed",
                            valueColor = colors.primary,
                        )
                    }
                }
            }
        }

        item(key = "global") {
            SectionLabel("全局")
            Block {
                MiuixCard {
                    MiuixRow(
                        title = "总开关",
                        subtitle = "关闭后所有 Hook 直接放行",
                        trailing = {
                            MiuixSwitch(
                                checked = state.policy.global.enabled,
                                onCheckedChange = onToggleEnabled,
                            )
                        },
                    )
                    MiuixDivider()
                    MiuixRow(
                        title = "退后台立即冻结",
                        subtitle = "仅对「立即冻结」模式的 App 生效",
                        trailing = {
                            MiuixSwitch(
                                checked = state.policy.global.forceFreezeOnBackground,
                                onCheckedChange = onToggleForceBg,
                            )
                        },
                    )
                    MiuixDivider()
                    MiuixRow(
                        title = "记录事件",
                        subtitle = "把 freezeUid 的真实返回串写回事件环",
                        trailing = {
                            MiuixSwitch(
                                checked = state.policy.global.audit,
                                onCheckedChange = onToggleAudit,
                            )
                        },
                    )
                    MiuixDivider()
                    MiuixRow(
                        title = "补写 GreeZe 名单",
                        subtitle = "实验性：写进 mNoRestrictAppSet，云控会覆盖",
                        trailing = {
                            MiuixSwitch(
                                checked = state.policy.global.patchLists,
                                onCheckedChange = onTogglePatchLists,
                            )
                        },
                    )
                    MiuixDivider()
                    MiuixRow(
                        title = "默认冻结延时",
                        value = "${state.policy.global.defaultDelayMs} ms",
                        subtitle = "策略 revision ${state.policy.revision}",
                    )
                }
            }
        }

        item(key = "actions") {
            Gap()
            Block {
                MiuixCard {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MiuixButton(
                            text = "全部解冻",
                            onClick = onThawAll,
                            modifier = Modifier.weight(1f),
                            fillWidth = true,
                        )
                        MiuixButton(
                            text = "安全模式",
                            onClick = onRequestSafeMode,
                            modifier = Modifier.weight(1f),
                            style = ButtonStyle.SECONDARY,
                            fillWidth = true,
                        )
                    }
                }
            }
        }

        item(key = "modes") {
            SectionLabel("GreeZe 模式")
            Block {
                MiuixCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        MiuixText(
                            text = "triggerQuickFreezeMode(mode, uid, reason) 的原样转发；mode 取值需真机确证。",
                            style = MiuixType.tiny,
                            color = colors.textSecondary,
                        )
                        Gap(8)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MODE_TOKENS.forEach { token ->
                                MiuixChip(text = token, selected = false, onClick = { onQuickMode(token) })
                            }
                        }
                    }
                }
            }
        }

        item(key = "recent") {
            SectionLabel("最近事件")
            Block {
                MiuixCard {
                    if (state.events.isEmpty()) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            MiuixText("暂无事件", style = MiuixType.rowValue, color = colors.textHint)
                        }
                    } else {
                        state.events.take(6).forEachIndexed { index, event ->
                            if (index > 0) MiuixDivider()
                            EventLine(event)
                        }
                    }
                }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.pagePadding)) {
        Spacer(Modifier.height(Dimens.sectionTitleTop))
        MiuixText(text, style = MiuixType.smallSectionTitle, color = MiuixTheme.colors.textSecondary)
        Spacer(Modifier.height(Dimens.sectionTitleBottom))
    }
}

@Composable
fun EventsPage(
    state: UiState,
    onClear: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(0) }
    val filtered = remember(state.events, filter) {
        state.events.filter { event ->
            when (filter) {
                1 -> event.type == EventType.FREEZE
                2 -> event.type == EventType.SKIP || event.type == EventType.BLOCK
                3 -> event.type == EventType.ERROR
                else -> true
            }
        }
    }
    val labels = listOf("全部", "冻结", "跳过", "错误")

    PageScaffold(title = "事件", subtitle = "最近 ${state.events.size} 条 · 上限 60（compactEvents 时 20）") {
        item(key = "filters") {
            Block {
                Gap(4)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    labels.forEachIndexed { index, label ->
                        MiuixChip(text = label, selected = filter == index, onClick = { filter = index })
                    }
                }
                Gap()
                MiuixButton(
                    text = "清空事件",
                    onClick = onClear,
                    style = ButtonStyle.TEXT,
                    fillWidth = false,
                )
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                MiuixEmptyState(
                    title = "暂无事件",
                    description = "Hook 生效时，冻结、跳过与失败原因都会记录在这里",
                    icon = HgIcons.Events,
                )
            }
        }

        items(items = filtered) { event ->
            Block {
                MiuixCard { EventLine(event) }
            }
            Gap(6)
        }
    }
}

@Composable
fun DiagnosticsPage(
    state: UiState,
    onSelfTest: () -> String,
    report: () -> String,
    onRefresh: () -> Unit,
    onRequestSafeMode: () -> Unit,
    onClearSafeMode: () -> Unit,
    onApplyLists: () -> Unit,
    onToggleFilterPolarity: (Boolean) -> Unit,
) {
    val colors = MiuixTheme.colors
    val clipboard = LocalClipboardManager.current
    var selfTest by remember { mutableStateOf<String?>(null) }
    val hb = state.heartbeat

    PageScaffold(title = "诊断", subtitle = "Hook 命中、能力探测与日志") {
        item(key = "env") {
            Block {
                Gap(4)
                MiuixCard {
                    MiuixRow("模块版本", value = hb?.moduleVersion ?: "—")
                    MiuixDivider()
                    MiuixRow("框架", value = (hb?.frameworkName ?: "—") + " " + (hb?.frameworkVersion ?: ""))
                    MiuixRow("API", value = "${hb?.apiVersion ?: 0}")
                    MiuixDivider()
                    MiuixRow("进程", value = hb?.processName ?: "—")
                    MiuixDivider()
                    MiuixRow("安全模式", value = if (hb?.safeMode == true) "是" else "否")
                    MiuixDivider()
                    MiuixRow("启动守卫计数", value = "${hb?.bootGuard ?: 0}")
                    MiuixDivider()
                    MiuixRow("策略 revision", value = "${hb?.revision ?: 0}")
                    MiuixDivider()
                    MiuixRow("构建指纹", subtitle = hb?.buildFingerprint ?: "—", value = "")
                }
            }
        }

        item(key = "hooks") {
            SectionLabel("Hook 落点")
            Block {
                MiuixCard {
                    val hooks = hb?.hooks.orEmpty()
                    if (hooks.isEmpty()) {
                        MiuixRow("尚未收到心跳", subtitle = "确认 LSPosed 作用域含「系统框架」并重启")
                    } else {
                        hooks.forEachIndexed { index, hook ->
                            if (index > 0) MiuixDivider()
                            MiuixRow(
                                title = hook.id,
                                subtitle = hook.note ?: hook.target,
                                value = "hits ${hook.hits}",
                                valueColor = when (hook.state) {
                                    HookState.INSTALLED -> colors.success
                                    HookState.ERROR -> colors.danger
                                    else -> colors.textHint
                                },
                                trailing = {
                                    MiuixBadge(
                                        text = hook.state,
                                        color = when (hook.state) {
                                            HookState.INSTALLED -> colors.success
                                            HookState.ERROR -> colors.danger
                                            HookState.MISSING -> colors.warning
                                            else -> colors.textHint
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        item(key = "caps") {
            SectionLabel("能力探测")
            Block {
                MiuixCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val caps = hb?.capabilities.orEmpty()
                        if (caps.isEmpty()) {
                            MiuixText("—", style = MiuixType.rowValue, color = colors.textHint)
                        } else {
                            caps.entries.chunked(2).forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    pair.forEach { (key, value) ->
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            MiuixBadge(
                                                text = if (value) "✓" else "✗",
                                                color = if (value) colors.success else colors.textHint,
                                            )
                                            Spacer(Modifier.padding(start = 6.dp))
                                            MiuixText(key, style = MiuixType.tiny, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "writeselftest") {
            SectionLabel("写入通道")
            Block {
                MiuixCard {
                    MiuixRow(
                        title = "WRITE_SETTINGS",
                        value = if (state.canWriteSettings) "已授权" else "未授权",
                        valueColor = if (state.canWriteSettings) colors.success else colors.danger,
                    )
                    MiuixDivider()
                    MiuixRow(
                        title = "写入自检",
                        subtitle = selfTest,
                        value = "运行",
                        onClick = { selfTest = onSelfTest() },
                    )
                    MiuixDivider()
                    MiuixRow(
                        title = "反射 filter 极性反转",
                        subtitle = "掩码行为与预期相反时才打开（只反转模块的改写，不改系统答案）",
                        trailing = {
                            MiuixSwitch(
                                checked = state.policy.global.filterPolarityInverted,
                                onCheckedChange = onToggleFilterPolarity,
                            )
                        },
                    )
                    MiuixDivider()
                    MiuixRow(
                        title = "刷新状态",
                        subtitle = "重新读取策略 / 心跳 / 事件",
                        onClick = onRefresh,
                    )
                }
            }
        }

        item(key = "ops") {
            SectionLabel("运维")
            Block {
                MiuixCard {
                    MiuixRow(
                        title = "导出诊断报告",
                        subtitle = "复制到剪贴板",
                        onClick = { clipboard.setText(AnnotatedString(report())) },
                    )
                    MiuixDivider()
                    MiuixRow(
                        title = "补写 GreeZe 名单",
                        subtitle = "把 never / immediate 集合写入服务字段",
                        onClick = onApplyLists,
                    )
                    MiuixDivider()
                    MiuixRow(title = "请求安全模式", subtitle = "下次开机 hook 不接管", onClick = onRequestSafeMode)
                    MiuixDivider()
                    MiuixRow(title = "解除安全模式", subtitle = "清空守卫计数并立即恢复", onClick = onClearSafeMode)
                }
            }
        }

        item(key = "logs") {
            SectionLabel("日志")
            Block {
                MiuixCard {
                    if (state.logs.isEmpty()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            MiuixText("暂无日志", style = MiuixType.rowValue, color = colors.textHint)
                        }
                    } else {
                        Column(modifier = Modifier.padding(12.dp)) {
                            state.logs.takeLast(24).forEach { line ->
                                MiuixText(
                                    text = line,
                                    style = MiuixType.mono,
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(16.dp)) }
    }
}
