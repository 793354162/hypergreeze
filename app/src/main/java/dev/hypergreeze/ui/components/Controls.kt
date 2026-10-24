package dev.hypergreeze.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.hypergreeze.ui.theme.Dimens
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.theme.MiuixType
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Switch -Miuix geometry: 50x28 track, 20dp thumb, 4dp inset, travel 22dp.
// ---------------------------------------------------------------------------------------------

@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val travel = Dimens.switchWidth - Dimens.switchThumb - Dimens.switchInset * 2
    val offset by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 987f),
        label = "switch-offset",
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled && checked -> colors.switchTrackOnDisabled
            !enabled -> colors.switchTrackOffDisabled
            checked -> colors.switchTrackOn
            else -> colors.switchTrackOff
        },
        animationSpec = spring(dampingRatio = 0.99f, stiffness = 438.6f),
        label = "switch-track",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 987f),
        label = "switch-thumb",
    )

    Box(
        modifier = modifier
            .size(Dimens.switchWidth, Dimens.switchHeight)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) { onCheckedChange(!checked) }
            .padding(horizontal = Dimens.switchInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = offset)
                .size(Dimens.switchThumb)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .background(colors.switchThumb, CircleShape),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, TEXT }

@Composable
fun MiuixButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    val colors = MiuixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "button-scale",
    )
    val background = when {
        !enabled && style != ButtonStyle.TEXT -> colors.secondaryContainer
        style == ButtonStyle.PRIMARY -> colors.primary
        style == ButtonStyle.SECONDARY -> colors.secondaryContainer
        style == ButtonStyle.DANGER -> colors.danger
        else -> Color.Transparent
    }
    val content = when {
        !enabled && style != ButtonStyle.TEXT -> colors.textDisabled
        style == ButtonStyle.PRIMARY || style == ButtonStyle.DANGER -> colors.onPrimary
        style == ButtonStyle.SECONDARY -> colors.onSecondaryContainer
        else -> colors.primary
    }
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(IntrinsicMinWidth(style)))
            .heightIn(min = Dimens.buttonMinHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(if (style == ButtonStyle.TEXT) RoundedCornerShape(8.dp) else CircleShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = if (style == ButtonStyle.TEXT) 10.dp else Dimens.buttonPaddingH, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(text = text, style = MiuixType.button, color = content, maxLines = 1)
    }
}

private fun IntrinsicMinWidth(style: ButtonStyle) = when (style) {
    ButtonStyle.TEXT -> 58.dp
    else -> 96.dp
}

// ---------------------------------------------------------------------------------------------
// Segmented control -container 40dp / item 32dp / radii 12 & 8
// ---------------------------------------------------------------------------------------------

@Composable
fun MiuixSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.segmentedHeight)
            .background(colors.secondaryContainer, RoundedCornerShape(Dimens.segmentedContainerRadius))
            .padding(Dimens.segmentedPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val interaction = remember(label) { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Dimens.segmentedItemHeight)
                    .clip(RoundedCornerShape(Dimens.segmentedItemRadius))
                    .background(if (selected) colors.card else Color.Transparent)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                    ) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                MiuixText(
                    text = label,
                    style = MiuixType.caption,
                    color = when {
                        !enabled -> colors.textDisabled
                        selected -> colors.textPrimary
                        else -> colors.textSecondary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Slider -28dp tall, 10dp knob, custom drawing so it matches Miuix instead of Material
// ---------------------------------------------------------------------------------------------

@Composable
fun MiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    step: Float = 0f,
) {
    val colors = MiuixTheme.colors
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    fun snap(raw: Float): Float {
        val clamped = raw.coerceIn(0f, 1f)
        val mapped = valueRange.start + clamped * span
        return if (step > 0f) {
            val steps = ((mapped - valueRange.start) / step).roundToInt()
            (valueRange.start + steps * step).coerceIn(valueRange.start, valueRange.endInclusive)
        } else {
            mapped
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.sliderHeight)
            .pointerInput(valueRange, step) {
                detectTapGestures { offset ->
                    if (size.width > 0) onValueChange(snap(offset.x / size.width))
                }
            }
            .pointerInput(valueRange, step) {
                detectHorizontalDragGestures { change, _ ->
                    if (size.width > 0) onValueChange(snap(change.position.x / size.width))
                }
            },
    ) {
        val centerY = size.height / 2f
        val trackHeight = 6.dp.toPx()
        val radius = 3.dp.toPx()
        val knob = Dimens.sliderKnobRadius.toPx()
        val maxX = (size.width - knob).coerceAtLeast(knob)
        val x = (size.width * fraction).coerceIn(knob, maxX)

        drawRoundRect(
            color = colors.sliderTrack,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(radius),
        )
        drawRoundRect(
            color = colors.primary,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(x, trackHeight),
            cornerRadius = CornerRadius(radius),
        )
        drawCircle(color = colors.switchThumb, radius = knob, center = Offset(x, centerY))
        drawCircle(
            color = colors.primary,
            radius = knob - 1.dp.toPx() / 2f,
            center = Offset(x, centerY),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Small chips / tri-state selector used by the 32-bit mask editor
// ---------------------------------------------------------------------------------------------

enum class BitState { NONE, KEEP, DROP }

@Composable
fun MiuixTriSelector(
    state: BitState,
    onSelect: (BitState) -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    val colors = MiuixTheme.colors
    Row(
        modifier = modifier
            .height(28.dp)
            .background(colors.secondaryContainer, RoundedCornerShape(8.dp))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentChip("不改", state == BitState.NONE, colors.textSecondary) { onSelect(BitState.NONE) }
        SegmentChip("豁免", state == BitState.KEEP, colors.success) { onSelect(BitState.KEEP) }
        SegmentChip(
            text = if (locked) "锁定" else "不豁免",
            selected = state == BitState.DROP,
            accent = if (locked) colors.textDisabled else colors.warning,
            enabled = !locked,
        ) { onSelect(BitState.DROP) }
    }
}

@Composable
private fun SegmentChip(
    text: String,
    selected: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.card else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(
            text = text,
            style = MiuixType.tiny,
            color = if (selected) accent else colors.textHint,
            maxLines = 1,
        )
    }
}

@Composable
fun MiuixChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primaryContainer else colors.card)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(
            text = text,
            style = MiuixType.caption,
            color = if (selected) colors.onPrimaryContainer else colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
fun MiuixTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(45.dp)
            .background(colors.card, Shapes.field)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(8.dp))
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MiuixType.rowValue.copy(color = colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        MiuixText(placeholder, style = MiuixType.rowValue, color = colors.textHint)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
fun MiuixFillSpacer() {
    Spacer(Modifier.fillMaxSize())
}

@Composable
fun MiuixVerticalGap(height: androidx.compose.ui.unit.Dp = Dimens.cardSpacing) {
    Spacer(Modifier.height(height))
}

@Composable
fun MiuixHorizontalGap(width: androidx.compose.ui.unit.Dp = 8.dp) {
    Spacer(Modifier.width(width))
}

/** Used by pages that lay out small stat cards side by side. */
@Composable
fun MiuixRowSpaced(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.cardSpacing), content = content)
}
