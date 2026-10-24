package dev.hypergreeze.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.hypergreeze.core.FreezeState
import dev.hypergreeze.ui.theme.Dimens
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.theme.MiuixType
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Squircle: port of miuix's `Path.addSquircleRect` (SquirclePath.kt, Apache-2.0).
// A circular `RoundedCornerShape` makes large cards look visibly "pointy" at the corners; Miuix
// uses a continuous-curvature corner with a cubic handle ratio of 0.643 and a 1.1x corner tile.
// ---------------------------------------------------------------------------------------------

private const val SQUIRCLE_CONTROL = 0.643f
private const val SQUIRCLE_EXTENSION = 1.1f

private fun squirclePath(width: Float, height: Float, cornerRadius: Float): Path {
    val path = Path()
    if (width <= 0f || height <= 0f) return path
    val tileBase = max(0f, cornerRadius) * SQUIRCLE_EXTENSION
    val tile = tileBase.coerceAtMost(min(width, height) * 0.5f)
    if (tile <= 0f) {
        path.addRect(Rect(0f, 0f, width, height))
        return path
    }
    val handle = tile * (1f - SQUIRCLE_CONTROL)
    path.moveTo(tile, 0f)
    path.lineTo(width - tile, 0f)
    path.cubicTo(width - handle, 0f, width, handle, width, tile)
    path.lineTo(width, height - tile)
    path.cubicTo(width, height - handle, width - handle, height, width - tile, height)
    path.lineTo(tile, height)
    path.cubicTo(handle, height, 0f, height - handle, 0f, height - tile)
    path.lineTo(0f, tile)
    path.cubicTo(0f, handle, handle, 0f, tile, 0f)
    path.close()
    return path
}

fun squircleShape(radius: Dp): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val px = with(density) { radius.toPx() }
        return Outline.Generic(squirclePath(size.width, size.height, px))
    }
}

object Shapes {
    val card: Shape = squircleShape(Dimens.cardRadius)
    val dialog: Shape = squircleShape(Dimens.dialogRadius)
    val sheet: Shape = squircleShape(Dimens.sheetRadius)
    val field: Shape = squircleShape(Dimens.inputRadius)
    val segmentedContainer = RoundedCornerShape(Dimens.segmentedContainerRadius)
    val segmentedItem = RoundedCornerShape(Dimens.segmentedItemRadius)
    val pill = CircleShape
    val tiny = RoundedCornerShape(6.dp)
}

// ---------------------------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------------------------

@Composable
fun MiuixText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MiuixType.body,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val resolved = if (color == Color.Unspecified && textAlign == null) {
        style
    } else {
        style.copy(
            color = if (color == Color.Unspecified) style.color else color,
            textAlign = textAlign ?: style.textAlign,
        )
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = resolved,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun MiuixIcon(
    vector: ImageVector,
    tint: Color = MiuixTheme.colors.textSecondary,
    size: Dp = Dimens.iconMd,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

// ---------------------------------------------------------------------------------------------
// Card / list surfaces
// ---------------------------------------------------------------------------------------------

@Composable
fun MiuixCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "card-scale",
    )
    val background = if (pressed && onClick != null) colors.cardPressed else colors.card
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(background, Shapes.card)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun MiuixSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.sectionTitleTop,
                end = Dimens.pagePadding,
                top = Dimens.sectionTitleTop,
                bottom = Dimens.sectionTitleBottom,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiuixText(
            text = text,
            style = MiuixType.smallSectionTitle,
            color = MiuixTheme.colors.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun MiuixCardTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.rowPaddingH, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MiuixText(title, style = MiuixType.rowTitle)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    subtitle,
                    style = MiuixType.rowSubtitle,
                    color = MiuixTheme.colors.textSecondary,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun MiuixDivider(insetStart: Dp = Dimens.dividerInset) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = insetStart)
            .height(Dimens.dividerThickness)
            .background(MiuixTheme.colors.divider),
    )
}

/**
 * Standard Miuix list row: `[leading] title/value [trailing]`.
 * Only one right-aligned group is allowed -this is the rule that keeps the list readable.
 */
@Composable
fun MiuixRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = MiuixTheme.colors.textSecondary,
    showArrow: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = Dimens.rowMinHeight)
        .then(
            if (onClick != null && enabled) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
            } else {
                Modifier
            },
        )
        .padding(horizontal = Dimens.rowPaddingH, vertical = Dimens.rowPaddingV)
    Row(
        modifier = modifier.then(rowModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            MiuixText(
                text = title,
                style = MiuixType.rowTitle,
                color = if (enabled) colors.textPrimary else colors.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    text = subtitle,
                    style = MiuixType.rowSubtitle,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            MiuixText(
                text = value,
                style = MiuixType.rowValue,
                color = if (enabled) valueColor else colors.textDisabled,
                maxLines = 1,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (showArrow) {
            Spacer(Modifier.width(4.dp))
            MiuixIcon(
                vector = HgIcons.ChevronRight,
                tint = if (pressed) colors.textSecondary else colors.textHint,
                size = Dimens.iconSm,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Badges / chips
// ---------------------------------------------------------------------------------------------

@Composable
fun MiuixBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    container: Color = color.copy(alpha = 0.10f),
) {
    Box(
        modifier = modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        MiuixText(text, style = MiuixType.tiny, color = color, maxLines = 1)
    }
}

@Composable
fun FreezeBadge(state: FreezeState, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    val accent = colors.freezeAccent(state)
    Row(
        modifier = modifier
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.dotSize)
                .background(accent, CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        MiuixText(state.label, style = MiuixType.tiny, color = accent, maxLines = 1)
    }
}

@Composable
fun MiuixDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(Dimens.dotSize).background(color, CircleShape))
}

// ---------------------------------------------------------------------------------------------
// Notice / empty
// ---------------------------------------------------------------------------------------------

@Composable
fun MiuixNoticeBar(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colors
    val (accent, container) = when (tone) {
        NoticeTone.INFO -> colors.info to colors.info.copy(alpha = 0.08f)
        NoticeTone.WARNING -> colors.warning to colors.warning.copy(alpha = 0.10f)
        NoticeTone.DANGER -> colors.danger to colors.danger.copy(alpha = 0.10f)
        NoticeTone.SUCCESS -> colors.success to colors.success.copy(alpha = 0.10f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(Dimens.cardRadius))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .background(accent, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        MiuixText(
            text = text,
            style = MiuixType.caption,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            MiuixText(
                text = actionLabel,
                style = MiuixType.caption,
                color = accent,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onAction,
                ),
            )
        }
    }
}

enum class NoticeTone { INFO, WARNING, DANGER, SUCCESS }

@Composable
fun MiuixEmptyState(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.emptyIllustration)
                .background(colors.secondaryContainer, Shapes.pill),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                MiuixIcon(vector = icon, tint = colors.textHint, size = 28.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        MiuixText(title, style = MiuixType.rowTitle, color = colors.textPrimary)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            MiuixText(
                text = description,
                style = MiuixType.caption,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .clip(Shapes.pill)
                    .background(colors.secondaryContainer)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAction,
                    )
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) {
                MiuixText(actionLabel, style = MiuixType.button, color = colors.primary, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screen scaffolding
// ---------------------------------------------------------------------------------------------

@Composable
fun MiuixPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pagePadding),
        content = content,
    )
}

@Composable
fun MiuixSpacer(height: Dp) {
    Spacer(Modifier.height(height))
}

@Composable
fun MiuixValueStack(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MiuixTheme.colors.primary,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        MiuixText(value, style = MiuixType.number, color = accent)
        Spacer(Modifier.height(2.dp))
        MiuixText(label, style = MiuixType.tiny, color = MiuixTheme.colors.textSecondary)
    }
}

/** dp -> px helper used by the custom slider. */
@Composable
fun dpToPx(value: Dp): Float = with(LocalDensity.current) { value.toPx() }
