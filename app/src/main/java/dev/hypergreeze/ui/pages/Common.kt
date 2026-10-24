package dev.hypergreeze.ui.pages

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.hypergreeze.core.EventRecord
import dev.hypergreeze.core.EventType
import dev.hypergreeze.ui.components.MiuixText
import dev.hypergreeze.ui.theme.Dimens
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.theme.MiuixType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Horizontal page padding wrapper used by every card row in a page. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    val blockColor = colors.secondaryContainer.copy(alpha = alpha)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.rowPaddingH, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(blockColor, androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .height(12.dp)
                    .background(blockColor, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.height(7.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.66f)
                    .height(10.dp)
                    .background(blockColor, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)),
            )
        }
    }
}

@Composable
fun Block(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pagePadding),
        content = content,
    )
}

@Composable
fun Gap(height: Int = 8) {
    Spacer(Modifier.height(height.dp))
}

@Composable
fun StatTile(
    label: String,
    value: String,
    accent: Color = MiuixTheme.colors.primary,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MiuixText(value, style = MiuixType.numberSmall, color = accent, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        MiuixText(label, style = MiuixType.tiny, color = MiuixTheme.colors.textSecondary, maxLines = 1)
    }
}

@Composable
fun StatRow(vararg tiles: Triple<String, String, Color>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tiles.forEach { (label, value, accent) ->
            StatTile(label = label, value = value, accent = accent, modifier = Modifier.weight(1f))
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

fun formatTime(ts: Long): String = if (ts <= 0) "--:--:--" else timeFormat.format(Date(ts))

@Composable
fun eventColor(type: String): Color = when (type) {
    EventType.FREEZE -> MiuixTheme.colors.frozenFully
    EventType.SKIP -> MiuixTheme.colors.warning
    EventType.BLOCK -> MiuixTheme.colors.warning
    EventType.ERROR -> MiuixTheme.colors.danger
    EventType.THAW -> MiuixTheme.colors.unfrozen
    EventType.BOOT -> MiuixTheme.colors.primary
    else -> MiuixTheme.colors.textHint
}

@Composable
fun EventLine(event: EventRecord, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.rowPaddingH, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).background(eventColor(event.type), CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            val title = buildString {
                append(event.type)
                event.pkg?.let { append("  ").append(it) }
            }
            MiuixText(title, style = MiuixType.rowValue, maxLines = 1)
            val detail = listOfNotNull(
                event.uid.takeIf { it != 0 }?.let { "uid=$it" },
                event.reason.takeIf { it != 0 }?.let { "reason=$it" },
                event.result,
                event.detail,
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    detail,
                    style = MiuixType.tiny,
                    color = colors.textSecondary,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        MiuixText(formatTime(event.ts), style = MiuixType.tiny, color = colors.textHint, maxLines = 1)
    }
}
