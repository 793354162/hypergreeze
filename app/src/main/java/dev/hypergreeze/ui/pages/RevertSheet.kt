package dev.hypergreeze.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hypergreeze.ui.components.MiuixButton
import dev.hypergreeze.ui.components.MiuixCard
import dev.hypergreeze.ui.components.MiuixDivider
import dev.hypergreeze.ui.components.MiuixNoticeBar
import dev.hypergreeze.ui.components.MiuixText
import dev.hypergreeze.ui.components.NoticeTone
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.theme.MiuixType
import dev.hypergreeze.ui.vm.UiState

/**
 * The "undo" surface. Two very different promises, so two very different sheets:
 *
 *  - **还原** undoes what can be undone and pauses the module, but keeps the policy so it can be
 *    switched back on without redoing the configuration.
 *  - **彻底清理** additionally deletes every Settings key and system property the module owns,
 *    because those survive uninstall. It says so in plain words instead of hiding behind a
 *    "clean up" label.
 */
@Composable
fun RevertSheetContent(
    state: UiState,
    clearData: Boolean,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val undoable = state.undoable
    val irreversible = state.irreversible

    if (clearData) {
        MiuixNoticeBar(
            text = "卸载前用。除了还原，还会删除模块写入的所有设置项与系统属性；策略配置也会一并清空。",
            tone = NoticeTone.DANGER,
        )
    } else {
        MiuixNoticeBar(
            text = "会做四件事：解冻全部 → 反做资源/名单/binder 改动 → 暂停模块再次施加 → 清空账本。策略配置保留。",
            tone = NoticeTone.INFO,
        )
    }

    Gap(12)
    if (undoable.isEmpty()) {
        MiuixText(
            if (clearData) "账本为空，仍会执行全部解冻与清理设置项。" else "账本为空：没有需要还原的系统改动，仍会执行全部解冻。",
            style = MiuixType.caption,
            color = colors.textSecondary,
        )
    } else {
        MiuixCard {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                undoable.take(12).forEachIndexed { index, entry ->
                    if (index > 0) MiuixDivider()
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        MiuixText(
                            "${entry.op.label} · ${entry.target}",
                            style = MiuixType.rowValue,
                            color = colors.textPrimary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(2.dp))
                        MiuixText(
                            buildString {
                                append(entry.oneLine)
                                if (!entry.before.isNullOrBlank()) append("（原值 ").append(entry.before).append("）")
                            },
                            style = MiuixType.tiny,
                            color = colors.textSecondary,
                            maxLines = 2,
                        )
                    }
                }
                if (undoable.size > 12) {
                    MiuixDivider()
                    Column(modifier = Modifier.padding(14.dp)) {
                        MiuixText(
                            "还有 ${undoable.size - 12} 项未列出，会一并还原",
                            style = MiuixType.tiny,
                            color = colors.textHint,
                        )
                    }
                }
            }
        }
    }

    if (irreversible.isNotEmpty()) {
        Gap(8)
        val labels = irreversible.map { it.op.label }.distinct().joinToString("、")
        MiuixNoticeBar(
            text = "这 ${irreversible.size} 项无法撤回：$labels。它们没有逆接口，重启后会由系统自行重建。",
            tone = NoticeTone.WARNING,
        )
    }

    Gap(16)
    MiuixButton(
        text = if (clearData) "确认彻底清理" else "确认还原",
        onClick = onConfirm,
        fillWidth = true,
        style = if (clearData) dev.hypergreeze.ui.components.ButtonStyle.DANGER else dev.hypergreeze.ui.components.ButtonStyle.PRIMARY,
    )
    Gap(8)
    MiuixButton(
        text = "取消",
        onClick = onClose,
        fillWidth = true,
        style = dev.hypergreeze.ui.components.ButtonStyle.SECONDARY,
    )
}
