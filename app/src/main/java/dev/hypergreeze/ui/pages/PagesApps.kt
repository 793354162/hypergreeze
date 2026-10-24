package dev.hypergreeze.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hypergreeze.core.AppPolicy
import dev.hypergreeze.core.Explain
import dev.hypergreeze.core.FreezeMode
import dev.hypergreeze.core.ResourceSwitches
import dev.hypergreeze.core.SceneBit
import dev.hypergreeze.core.ThawTriggers
import dev.hypergreeze.ui.components.BitState
import dev.hypergreeze.ui.components.ButtonStyle
import dev.hypergreeze.ui.components.HgIcons
import dev.hypergreeze.ui.components.MiuixBadge
import dev.hypergreeze.ui.components.MiuixBottomSheet
import dev.hypergreeze.ui.components.MiuixButton
import dev.hypergreeze.ui.components.MiuixCard
import dev.hypergreeze.ui.components.MiuixChip
import dev.hypergreeze.ui.components.MiuixDivider
import dev.hypergreeze.ui.components.MiuixEmptyState
import dev.hypergreeze.ui.components.MiuixRow
import dev.hypergreeze.ui.components.MiuixSegmented
import dev.hypergreeze.ui.components.MiuixSlider
import dev.hypergreeze.ui.components.MiuixSwitch
import dev.hypergreeze.ui.components.MiuixText
import dev.hypergreeze.ui.components.MiuixTextField
import dev.hypergreeze.ui.components.MiuixTriSelector
import dev.hypergreeze.ui.components.PageScaffold
import dev.hypergreeze.ui.data.IconCache
import dev.hypergreeze.ui.theme.Dimens
import dev.hypergreeze.ui.theme.MiuixColors
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.theme.MiuixType
import dev.hypergreeze.ui.vm.MainViewModel
import dev.hypergreeze.ui.vm.UiState

@Composable
fun AppIcon(pkg: String, size: Dp = 36.dp) {
    val context = LocalContext.current
    val bitmap = remember(pkg) { IconCache.icon(context, pkg) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(MiuixTheme.colors.secondaryContainer, CircleShape),
        )
    }
}

private fun modeColor(mode: FreezeMode, colors: MiuixColors) = when (mode) {
    FreezeMode.FOLLOW -> colors.textHint
    FreezeMode.NEVER -> colors.unfrozen
    FreezeMode.IMMEDIATE -> colors.frozenFully
    FreezeMode.CUSTOM -> colors.frozenPartial
}

@Composable
fun AppsPage(
    state: UiState,
    onOpen: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    val filtered = remember(state.apps, state.policy, query, filter) {
        val q = query.trim().lowercase()
        state.apps.filter { app ->
            val matchQuery = q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.pkg.lowercase().contains(q)
            val configured = state.policy.app(app.pkg)?.isEffective == true
            val matchFilter = when (filter) {
                1 -> configured
                2 -> app.system
                3 -> !app.system
                else -> true
            }
            matchQuery && matchFilter
        }
    }
    val colors = MiuixTheme.colors

    PageScaffold(
        title = "应用",
        subtitle = "共 ${state.apps.size} 个 · 已配置 ${state.configuredCount}",
    ) {
        item(key = "search") {
            Block {
                Gap(4)
                MiuixTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索应用名 / 包名",
                )
                Gap(8)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("全部", "已配置", "系统应用", "用户应用").forEachIndexed { index, label ->
                        MiuixChip(
                            text = label,
                            selected = filter == index,
                            onClick = { filter = index },
                        )
                    }
                }
            }
        }

        if (state.loading) {
            item(key = "loading") {
                Block {
                    repeat(6) {
                        MiuixCard { SkeletonRow() }
                        Gap(6)
                    }
                }
            }
        } else if (filtered.isEmpty()) {
            item(key = "empty") {
                MiuixEmptyState(
                    title = "没有匹配的应用",
                    description = if (filter == 1) "还没有为任何应用配置策略" else "换个关键词试试",
                    icon = HgIcons.Apps,
                )
            }
        }

        items(items = filtered, key = { it.pkg }) { app ->
            val policy = state.policy.app(app.pkg)
            val mode = policy?.mode ?: FreezeMode.FOLLOW
            Block {
                MiuixCard(onClick = { onOpen(app.pkg) }) {
                    MiuixRow(
                        title = app.label,
                        subtitle = "${app.pkg} · uid ${app.uid}",
                        leading = { AppIcon(app.pkg) },
                        value = mode.label,
                        valueColor = modeColor(mode, colors),
                        showArrow = true,
                    )
                }
            }
            Gap(6)
        }
    }
}

private fun resourceValueOf(app: AppPolicy, key: String): Boolean? = when (key) {
    "net" -> app.resources.net
    "alarm" -> app.resources.alarm
    "job" -> app.resources.job
    "wakelock" -> app.resources.wakelock
    "broadcast" -> app.resources.broadcast
    else -> app.resources.cpu
}

private fun thawValueOf(app: AppPolicy, key: String): Boolean? = when (key) {
    "screenOn" -> app.thawOn.screenOn
    "netChange" -> app.thawOn.netChange
    "notification" -> app.thawOn.notification
    "audioFocus" -> app.thawOn.audioFocus
    "charging" -> app.thawOn.charging
    else -> app.thawOn.externalWake
}

@Composable
fun AppDetailPage(
    pkg: String,
    state: UiState,
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val app = state.policy.appOrDefault(pkg)
    val entry = state.apps.firstOrNull { it.pkg == pkg }
    val expert = state.policy.global.uiExpert
    var sheet by rememberSaveable { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        PageScaffold(
            title = entry?.label ?: pkg,
            subtitle = "$pkg · uid ${entry?.uid ?: "?"}",
            onBack = onBack,
            headerExtra = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    MiuixBadge(text = app.mode.label, color = modeColorOf(app.mode, colors))
                }
            },
        ) {
            item(key = "explain") {
                Block {
                    Gap(4)
                    MiuixCard {
                        Column(modifier = Modifier.padding(14.dp)) {
                            MiuixText("这个应用现在会怎样", style = MiuixType.smallSectionTitle, color = colors.textSecondary)
                            Gap(6)
                            Explain.appLines(pkg, app, state.policy.global).forEach { line ->
                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    MiuixText("·", style = MiuixType.caption, color = colors.primary)
                                    Spacer(Modifier.width(6.dp))
                                    MiuixText(line, style = MiuixType.caption, color = colors.textPrimary)
                                }
                            }
                        }
                    }
                }
            }

            item(key = "mode") {
                Block {
                    Gap(4)
                    MiuixCard {
                        Column(modifier = Modifier.padding(12.dp)) {
                            MiuixText("冻结模式", style = MiuixType.rowTitle)
                            Gap(8)
                            MiuixSegmented(
                                options = FreezeMode.ordered.map { it.label },
                                selectedIndex = FreezeMode.ordered.indexOf(app.mode),
                                onSelect = { index -> vm.setAppMode(pkg, FreezeMode.ordered[index]) },
                            )
                            Gap(8)
                            MiuixText(
                                text = app.mode.description,
                                style = MiuixType.tiny,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }

            item(key = "timing") {
                if (!expert) {
                    // Simple mode: one slider, the one people actually change.
                    Block {
                        MiuixCard {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixText("退后台多久后冻结", style = MiuixType.rowTitle, modifier = Modifier.weight(1f))
                                    MiuixText("${app.delayMs} ms", style = MiuixType.rowValue, color = colors.primary)
                                }
                                MiuixSlider(
                                    value = app.delayMs.toFloat(),
                                    onValueChange = { value -> vm.updateApp(pkg) { it.copy(delayMs = value.toLong()) } },
                                    valueRange = 0f..3000f,
                                    step = 100f,
                                )
                                MiuixText(
                                    "0 ms = 一退到后台就冻结；只有「立即冻结」模式用得到这个值",
                                    style = MiuixType.tiny,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                    }
                } else {
                    DetailSectionTitle("时机")
                    Block {
                        MiuixCard {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixText("冻结延时", style = MiuixType.rowTitle, modifier = Modifier.weight(1f))
                                    MiuixText("${app.delayMs} ms", style = MiuixType.rowValue, color = colors.primary)
                                }
                                MiuixSlider(
                                    value = app.delayMs.toFloat(),
                                    onValueChange = { value -> vm.updateApp(pkg) { it.copy(delayMs = value.toLong()) } },
                                    valueRange = 0f..3000f,
                                    step = 100f,
                                )
                                Gap(8)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixText("反抖动冷却", style = MiuixType.rowTitle, modifier = Modifier.weight(1f))
                                    MiuixText("${app.thawCooldownMs} ms", style = MiuixType.rowValue, color = colors.primary)
                                }
                                MiuixSlider(
                                    value = app.thawCooldownMs.toFloat(),
                                    onValueChange = { value ->
                                        vm.updateApp(pkg) { it.copy(thawCooldownMs = value.toLong()) }
                                    },
                                    valueRange = 0f..60_000f,
                                    step = 1_000f,
                                )
                            }
                        }
                    }
                }
            }

            if (expert) {
                item(key = "mask") {
                    Block {
                        MiuixCard {
                            MiuixRow(
                                title = "场景掩码",
                                subtitle = "强制豁免 / 强制不豁免 的原因位",
                                value = "keep ${app.keepBits.size} · drop ${app.dropBits.size}",
                                showArrow = true,
                                onClick = { sheet = 1 },
                            )
                        }
                    }
                }

                item(key = "process") {
                    Block {
                        MiuixCard {
                            MiuixRow(
                                title = "子进程例外",
                                subtitle = "按完整进程名覆盖（如 :push）",
                                value = "${app.processes.size} 条",
                                showArrow = true,
                                onClick = { sheet = 2 },
                            )
                        }
                    }
                }

                item(key = "resources") {
                    DetailSectionTitle("资源联动")
                    Block {
                        MiuixCard {
                            ResourceSwitches.ORDER.forEachIndexed { index, entryRow ->
                                if (index > 0) MiuixDivider()
                                TriRow(
                                    title = entryRow.second,
                                    subtitle = Explain.resourceLine(entryRow.first, resourceValueOf(app, entryRow.first)),
                                    value = resourceValueOf(app, entryRow.first),
                                    onChange = { vm.setResource(pkg, entryRow.first, it) },
                                )
                            }
                        }
                    }
                }

                item(key = "thaw") {
                    DetailSectionTitle("解冻条件")
                    Block {
                        MiuixCard {
                            ThawTriggerBlocks(app) { key, value -> vm.setThaw(pkg, key, value) }
                        }
                    }
                }
            }

            item(key = "expert-toggle") {
                Gap(8)
                Block {
                    MiuixCard {
                        MiuixRow(
                            title = "专家选项",
                            subtitle = "显示 32 位掩码、子进程、资源联动、解冻条件",
                            trailing = {
                                MiuixSwitch(
                                    checked = expert,
                                    onCheckedChange = { value ->
                                        vm.setGlobal { g -> g.copy(uiExpert = value) }
                                    },
                                )
                            },
                        )
                    }
                }
            }

            item(key = "actions") {
                Gap(16)
                Block {
                    Column {
                        MiuixButton(
                            text = "立即冻结",
                            onClick = { vm.freezeNow(pkg) },
                            fillWidth = true,
                        )
                        Gap()
                        MiuixButton(
                            text = "解冻该应用",
                            onClick = { entry?.let { vm.thawUid(it.uid) } },
                            style = ButtonStyle.SECONDARY,
                            fillWidth = true,
                        )
                        Gap()
                        MiuixButton(
                            text = "恢复为跟随系统",
                            onClick = {
                                vm.resetApp(pkg)
                                onBack()
                            },
                            style = ButtonStyle.TEXT,
                            fillWidth = true,
                        )
                    }
                }
            }

            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }

        MiuixBottomSheet(
            visible = sheet == 1,
            title = "场景掩码 · ${entry?.label ?: pkg}",
            onDismiss = { sheet = 0 },
        ) {
            MaskSheetContent(app = app, pkg = pkg, vm = vm)
        }

        MiuixBottomSheet(
            visible = sheet == 2,
            title = "子进程例外",
            onDismiss = { sheet = 0 },
        ) {
            ProcessSheetContent(app = app, pkg = pkg, vm = vm)
        }
    }
}

@Composable
private fun ThawTriggerBlocks(app: AppPolicy, onChange: (String, Boolean?) -> Unit) {
    ThawTriggers.ORDER.forEachIndexed { index, row ->
        if (index > 0) MiuixDivider()
        val value = thawValueOf(app, row.first)
        TriRow(
            title = row.second,
            subtitle = Explain.thawLine(row.first, value),
            value = value,
            onChange = { onChange(row.first, it) },
        )
    }
}

@Composable
private fun DetailSectionTitle(text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.pagePadding)) {
        Spacer(Modifier.height(Dimens.sectionTitleTop))
        MiuixText(text, style = MiuixType.smallSectionTitle, color = MiuixTheme.colors.textSecondary)
        Spacer(Modifier.height(Dimens.sectionTitleBottom))
    }
}

@Composable
private fun MaskSheetContent(app: AppPolicy, pkg: String, vm: MainViewModel) {
    val colors = MiuixTheme.colors
    MiuixText(
        text = "keep = 强制豁免（这次不冻）；drop = 强制不豁免（即使 GreeZe 认为该放行）。" +
            "AUDIO / CAMERA / TOP / IME / VISIBLE 的 drop 位被锁定，关掉它们会让前台应用被冻住。",
        style = MiuixType.tiny,
        color = colors.textSecondary,
    )
    Gap(8)
    SceneBit.Group.entries.forEach { group ->
        val bits = SceneBit.entries.filter { it.group == group }
        if (bits.isEmpty()) return@forEach
        MiuixText(
            text = group.label,
            style = MiuixType.smallSectionTitle,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        bits.forEach { bit ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    MiuixText(bit.label, style = MiuixType.rowValue, maxLines = 1)
                    MiuixText(
                        text = "0x${bit.value.toString(16).uppercase()} = ${bit.value}",
                        style = MiuixType.tiny,
                        color = colors.textHint,
                    )
                }
                Spacer(Modifier.width(8.dp))
                MiuixTriSelector(
                    state = when {
                        bit in app.keepBits -> BitState.KEEP
                        bit in app.dropBits -> BitState.DROP
                        else -> BitState.NONE
                    },
                    locked = bit in SceneBit.LOCKED_DROP,
                    onSelect = { next ->
                        when (next) {
                            BitState.KEEP -> if (bit !in app.keepBits) vm.toggleKeepBit(pkg, bit)
                            BitState.DROP -> if (bit !in app.dropBits) vm.toggleDropBit(pkg, bit)
                            BitState.NONE -> {
                                if (bit in app.keepBits) vm.toggleKeepBit(pkg, bit)
                                if (bit in app.dropBits) vm.toggleDropBit(pkg, bit)
                            }
                        }
                    },
                )
            }
        }
    }
    Gap(12)
    MiuixText(
        text = "当前掩码：keep=0x${app.maskKeep.toString(16).uppercase()} " +
            "drop=0x${app.maskDrop.toString(16).uppercase()}",
        style = MiuixType.tiny,
        color = colors.textPrimary,
    )
}

@Composable
private fun ProcessSheetContent(app: AppPolicy, pkg: String, vm: MainViewModel) {
    val colors = MiuixTheme.colors
    var input by remember { mutableStateOf("") }
    MiuixText(
        text = "GreeZe 在运行时枚举子进程；这里登记的是覆盖规则，进程名必须完整（含冒号后缀）。",
        style = MiuixType.tiny,
        color = colors.textSecondary,
    )
    Gap(12)
    if (app.processes.isEmpty()) {
        MiuixText("尚未配置", style = MiuixType.rowValue, color = colors.textHint)
    } else {
        app.processes.toList().sortedBy { it.first }.forEach { row ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                MiuixText(row.first, style = MiuixType.rowValue, maxLines = 1)
                Gap(6)
                MiuixSegmented(
                    options = listOf("跟随", "永不冻结", "立即冻结"),
                    selectedIndex = when (row.second) {
                        FreezeMode.FOLLOW -> 0
                        FreezeMode.NEVER -> 1
                        else -> 2
                    },
                    onSelect = { index ->
                        val next = when (index) {
                            1 -> FreezeMode.NEVER
                            2 -> FreezeMode.IMMEDIATE
                            else -> FreezeMode.FOLLOW
                        }
                        vm.setProcessMode(pkg, row.first, next)
                    },
                )
            }
            MiuixDivider()
        }
    }
    Gap(12)
    MiuixTextField(
        value = input,
        onValueChange = { input = it },
        placeholder = "$pkg:push",
    )
    Gap(8)
    MiuixButton(
        text = "添加为「永不冻结」",
        onClick = {
            val name = input.trim()
            if (name.isNotEmpty()) {
                vm.setProcessMode(pkg, name, FreezeMode.NEVER)
                input = ""
            }
        },
        fillWidth = true,
    )
}
