package dev.hypergreeze.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hypergreeze.core.Explain
import dev.hypergreeze.core.BitRole
import dev.hypergreeze.core.SceneBit
import dev.hypergreeze.ui.components.MiuixCard
import dev.hypergreeze.ui.components.MiuixChip
import dev.hypergreeze.ui.components.MiuixDivider
import dev.hypergreeze.ui.components.MiuixNoticeBar
import dev.hypergreeze.ui.components.MiuixRow
import dev.hypergreeze.ui.components.MiuixSegmented
import dev.hypergreeze.ui.components.MiuixText
import dev.hypergreeze.ui.components.MiuixTextField
import dev.hypergreeze.ui.components.NoticeTone
import dev.hypergreeze.ui.components.PageScaffold
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.theme.MiuixType
import dev.hypergreeze.ui.vm.UiState

private val GLOSSARY = listOf(
    "跟随系统" to "模块完全不管这个应用，GreeZe 怎么判就怎么执行",
    "永不冻结" to "拒绝所有冻结请求；但推送能否及时到取决于系统，不保证",
    "立即冻结" to "退到后台后由模块主动下发冻结，可设延时与冷却",
    "自定义" to "不继承默认，只执行你打开的位与开关",
    "keep（豁免）" to "命中这个原因时**不冻**，例如正在放音频",
    "drop（不豁免）" to "命中这个原因时**照冻**，例如即使在白名单里",
    "原因位" to "GreeZe 给出的「为什么这次可以不冻」的标记，共 32 位",
    "锁定项" to "音频/相机/前台/输入法/可见窗口，不允许 drop，否则前台也会被冻",
    "账本" to "模块对外部改动的记录，用来还原",
    "安全模式" to "hook 仍然装载但全部放行，用于排查",
    "抑制（suppressed）" to "刚还原过，模块暂时不再施加资源限制，直到你改策略",
)

private val PREVIEW_BITS = listOf(
    SceneBit.AUDIO,
    SceneBit.CAMERA,
    SceneBit.GPS,
    SceneBit.TOP,
    SceneBit.VISIBLE,
    SceneBit.NO_RESTRICT,
    SceneBit.FG_CONNECTION,
    SceneBit.FOREGROUND_SERVICE,
)

/**
 * One page that answers "what does this switch actually do" and "what would the module decide
 * right now". Everything here is computed locally with the same rules the hook side uses
 * (`PolicyEngine` / `Explain`), so it works without the module being active.
 */
@Composable
fun HelpPage(
    state: UiState,
    onBack: () -> Unit,
) {
    val colors = MiuixTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf(0) }
    var original by rememberSaveable { mutableStateOf(true) }

    PageScaffold(
        title = "帮助与试算",
        subtitle = "不用等真机，先看模块会怎么判",
        onBack = onBack,
    ) {
        item(key = "preview") {
            Block {
                Gap(4)
                MiuixCard {
                    Column(modifier = Modifier.padding(14.dp)) {
                        MiuixText("策略试算", style = MiuixType.rowTitle)
                        Gap(4)
                        MiuixText(
                            "输入包名与 GreeZe 给出的原因位，看模块会怎么判定。纯本地计算，不影响系统。",
                            style = MiuixType.tiny,
                            color = colors.textSecondary,
                        )
                        Gap(8)
                        MiuixTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "com.tencent.mm",
                        )
                        Gap(8)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PREVIEW_BITS.forEach { bit ->
                                val selected = reason and bit.value != 0
                                MiuixChip(
                                    text = bit.label,
                                    selected = selected,
                                    onClick = {
                                        reason = if (selected) reason and bit.value.inv() else reason or bit.value
                                    },
                                )
                            }
                        }
                        Gap(10)
                        MiuixSegmented(
                            options = listOf("假设系统原本允许冻结", "假设系统原本拒绝"),
                            selectedIndex = if (original) 0 else 1,
                            onSelect = { original = it == 0 },
                        )
                        Gap(10)
                        val pkg = query.trim()
                        val verdict = if (pkg.isEmpty()) {
                            "先输入一个包名"
                        } else {
                            Explain.filterPreview(state.policy, pkg, reason, original)
                        }
                        MiuixNoticeBar(
                            text = verdict,
                            tone = when {
                                pkg.isEmpty() -> NoticeTone.INFO
                                verdict.startsWith("不会冻结") -> NoticeTone.SUCCESS
                                verdict.startsWith("会冻结") -> NoticeTone.WARNING
                                else -> NoticeTone.INFO
                            },
                        )
                        Gap(6)
                        MiuixText(
                            "原因位：${Explain.reasonLabel(reason)}",
                            style = MiuixType.tiny,
                            color = colors.textHint,
                        )
                    }
                }
            }
        }

        item(key = "glossary-title") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Spacer(Modifier.height(16.dp))
                MiuixText("术语表", style = MiuixType.smallSectionTitle, color = colors.textSecondary)
            }
        }
        item(key = "glossary") {
            Block {
                MiuixCard {
                    GLOSSARY.forEachIndexed { index, row ->
                        if (index > 0) MiuixDivider()
                        MiuixRow(title = row.first, subtitle = row.second)
                    }
                }
            }
        }

        item(key = "bits-title") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Spacer(Modifier.height(16.dp))
                MiuixText("场景位会带来什么后果", style = MiuixType.smallSectionTitle, color = colors.textSecondary)
            }
        }
        item(key = "bits") {
            Block {
                MiuixCard {
                    SceneBit.entries.filter { it.value != 1 }.forEachIndexed { index, bit ->
                        if (index > 0) MiuixDivider()
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiuixText(bit.label, style = MiuixType.rowValue, modifier = Modifier.weight(1f))
                                if (bit in SceneBit.LOCKED_DROP) {
                                    MiuixText("锁定", style = MiuixType.tiny, color = colors.warning)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            MiuixText(
                                "豁免：${Explain.bitEffect(bit, BitRole.KEEP)}",
                                style = MiuixType.tiny,
                                color = colors.textSecondary,
                            )
                            Spacer(Modifier.height(2.dp))
                            MiuixText(
                                "不豁免：${Explain.bitEffect(bit, BitRole.DROP)}",
                                style = MiuixType.tiny,
                                color = if (bit in SceneBit.LOCKED_DROP) colors.warning else colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}
