package dev.hypergreeze.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Miuix / HyperOS design tokens.
 *
 * Values are not invented: colours come from the miuix `Colors.kt` `primary` and the HyperOS
 * accent tokens (`accentBlueFill`, `accentRedFill`, …), dimensions from `CardDefaults`,
 * `ButtonDefaults`, `BasicComponentDefaults` and the switch implementation, and the type ramp from
 * HyperOS `fontSystem*` / miuix `TextStyles`. See docs/DESIGN_MIUIX.md for the per-token source.
 */
@Immutable
data class MiuixColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val background: Color,
    val card: Color,
    val cardPressed: Color,
    val divider: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val textDisabled: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val scrim: Color,
    val pressOverlay: Color,
    val sliderTrack: Color,
    val switchTrackOn: Color,
    val switchTrackOff: Color,
    val switchThumb: Color,
    val switchTrackOnDisabled: Color,
    val switchTrackOffDisabled: Color,
    val frozenFully: Color,
    val frozenPartial: Color,
    val unfrozen: Color,
    val isDark: Boolean,
) {
    fun freezeAccent(state: dev.hypergreeze.core.FreezeState): Color = when (state) {
        dev.hypergreeze.core.FreezeState.FULLY_FROZEN -> frozenFully
        dev.hypergreeze.core.FreezeState.PARTIALLY_FROZEN -> frozenPartial
        dev.hypergreeze.core.FreezeState.UNFROZEN -> unfrozen
        dev.hypergreeze.core.FreezeState.UNKNOWN -> textHint
    }
}

val MiuixLightColors = MiuixColors(
    primary = Color(0xFF3482FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAF2FF),
    onPrimaryContainer = Color(0xFF3482FF),
    secondary = Color(0xFFE6E6E6),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF303030),
    background = Color(0xFFF7F7F7),
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFEBEBEB),
    divider = Color(0xFFE0E0E0),
    outline = Color(0xFFD9D9D9),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0x99000000),
    textHint = Color(0x66000000),
    textDisabled = Color(0xFFB2B2B2),
    danger = Color(0xFFE94634),
    onDanger = Color(0xFFFFFFFF),
    dangerContainer = Color(0xFFFDF6F4),
    success = Color(0xFF0CCE94),
    warning = Color(0xFFFF6A05),
    info = Color(0xFF29A9FF),
    scrim = Color(0x4D000000),
    pressOverlay = Color(0x14000000),
    sliderTrack = Color(0x0F000000),
    switchTrackOn = Color(0xFF3482FF),
    switchTrackOff = Color(0xFFE6E6E6),
    switchThumb = Color(0xFFFFFFFF),
    switchTrackOnDisabled = Color(0xFFC2D9FF),
    switchTrackOffDisabled = Color(0xFFF0F0F0),
    frozenFully = Color(0xFF3482FF),
    frozenPartial = Color(0xFFDD5900),
    unfrozen = Color(0xFF00B884),
    isDark = false,
)

val MiuixDarkColors = MiuixColors(
    primary = Color(0xFF4788FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2B3B54),
    onPrimaryContainer = Color(0xFF4788FF),
    secondary = Color(0xFF505050),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF434343),
    onSecondaryContainer = Color(0xFFE6E6E6),
    background = Color(0xFF000000),
    card = Color(0xFF242424),
    cardPressed = Color(0xFF313131),
    divider = Color(0xFF393939),
    outline = Color(0xFF404040),
    textPrimary = Color(0xE6FFFFFF),
    textSecondary = Color(0x80FFFFFF),
    textHint = Color(0x66FFFFFF),
    textDisabled = Color(0xFF666666),
    danger = Color(0xFFF12522),
    onDanger = Color(0xFFFFFFFF),
    dangerContainer = Color(0xFF2E0603),
    success = Color(0xFF00BA7C),
    warning = Color(0xFFF97A1F),
    info = Color(0xFF36ACFC),
    scrim = Color(0x99000000),
    pressOverlay = Color(0x14FFFFFF),
    sliderTrack = Color(0x26FFFFFF),
    switchTrackOn = Color(0xFF4788FF),
    switchTrackOff = Color(0xFF505050),
    switchThumb = Color(0xFFFFFFFF),
    switchTrackOnDisabled = Color(0xFF253E64),
    switchTrackOffDisabled = Color(0xFF3F3F3F),
    frozenFully = Color(0xFF4788FF),
    frozenPartial = Color(0xFFF97A1F),
    unfrozen = Color(0xFF00BA7C),
    isDark = true,
)

/** Miuix dimensions. One object because Miuix has no theme-dependent sizes. */
object Dimens {
    val cardRadius = 16.dp
    val dialogRadius = 32.dp
    val sheetRadius = 28.dp
    val inputRadius = 16.dp
    val segmentedContainerRadius = 12.dp
    val segmentedItemRadius = 8.dp
    val chipRadius = 999.dp

    val pagePadding = 16.dp
    val cardSpacing = 8.dp
    val sectionSpacing = 24.dp
    val sectionTitleTop = 28.dp
    val sectionTitleBottom = 8.dp

    val rowMinHeight = 56.dp
    val rowPaddingH = 16.dp
    val rowPaddingV = 12.dp
    val dividerThickness = 0.75.dp
    val dividerInset = 16.dp
    val dividerInsetWithIcon = 44.dp

    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp

    val switchWidth = 50.dp
    val switchHeight = 28.dp
    val switchThumb = 20.dp
    val switchInset = 4.dp

    val buttonMinHeight = 46.dp
    val buttonPaddingH = 16.dp
    val segmentedHeight = 40.dp
    val segmentedItemHeight = 32.dp
    val segmentedPadding = 4.dp

    val sliderHeight = 28.dp
    val sliderKnobRadius = 10.dp
    val sliderKeyPointRadius = 4.dp

    val topBarCollapsed = 52.dp
    val topBarTitlePadding = 26.dp

    val badgeMinHeight = 18.dp
    val dotSize = 7.dp
    val emptyIllustration = 60.dp
    val sheetHandleWidth = 45.dp
    val sheetHandleHeight = 4.dp
    val snackbarRadius = 16.dp
}

/** HyperOS type ramp: `fontSystem32Medium`, `fontSystem16Medium`, `fontSystem13Regular`, … */
object MiuixType {
    private val family = FontFamily.SansSerif

    val titleLarge = TextStyle(
        fontFamily = family, fontSize = 32.sp, lineHeight = 42.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    )
    val titleCollapsed = TextStyle(
        fontFamily = family, fontSize = 20.sp, lineHeight = 27.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    )
    val sectionTitle = TextStyle(
        fontFamily = family, fontSize = 20.sp, lineHeight = 27.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
    )
    val smallSectionTitle = TextStyle(
        fontFamily = family, fontSize = 14.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.sp,
    )
    val rowTitle = TextStyle(
        fontFamily = family, fontSize = 16.sp, lineHeight = 21.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    )
    val rowSubtitle = TextStyle(
        fontFamily = family, fontSize = 13.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
    )
    val rowValue = TextStyle(
        fontFamily = family, fontSize = 14.sp, lineHeight = 19.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
    )
    val body = TextStyle(
        fontFamily = family, fontSize = 17.sp, lineHeight = 23.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
    )
    val button = TextStyle(
        fontFamily = family, fontSize = 17.sp, lineHeight = 23.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    )
    val caption = TextStyle(
        fontFamily = family, fontSize = 13.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
    )
    val tiny = TextStyle(
        fontFamily = family, fontSize = 11.sp, lineHeight = 15.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
    )
    val number = TextStyle(
        fontFamily = family, fontSize = 24.sp, lineHeight = 29.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    )
    val numberSmall = TextStyle(
        fontFamily = family, fontSize = 20.sp, lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    )
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
    )
}

val LocalMiuixColors = staticCompositionLocalOf { MiuixLightColors }

@Composable
fun HyperGreezeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) MiuixDarkColors else MiuixLightColors
    CompositionLocalProvider(LocalMiuixColors provides colors) {
        content()
    }
}

/** Convenience accessor used everywhere instead of a MaterialTheme colour object. */
object MiuixTheme {
    val colors: MiuixColors
        @Composable get() = LocalMiuixColors.current
}
