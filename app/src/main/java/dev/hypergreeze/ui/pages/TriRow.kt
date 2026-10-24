package dev.hypergreeze.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hypergreeze.ui.components.MiuixSegmented
import dev.hypergreeze.ui.components.MiuixText
import dev.hypergreeze.ui.theme.Dimens
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.theme.MiuixType

/**
 * Tri-state row: `null` = follow GreeZe, `true` / `false` = explicit. Used by both the resource
 * switches and the thaw triggers, because a plain on/off switch cannot express "leave it alone".
 */
@Composable
fun TriRow(
    title: String,
    subtitle: String? = null,
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.rowPaddingH, vertical = 10.dp),
    ) {
        MiuixText(title, style = MiuixType.rowTitle, maxLines = 1)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            MiuixText(subtitle, style = MiuixType.tiny, color = colors.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        MiuixSegmented(
            options = listOf("跟随", "开启", "关闭"),
            selectedIndex = when (value) {
                null -> 0
                true -> 1
                false -> 2
            },
            onSelect = { index ->
                onChange(
                    when (index) {
                        1 -> true
                        2 -> false
                        else -> null
                    },
                )
            },
        )
    }
}
