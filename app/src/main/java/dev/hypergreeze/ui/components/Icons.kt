package dev.hypergreeze.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-built icons.
 *
 * Deliberately not `androidx.compose.material:material-icons-core`: the module must not depend on a
 * Material artefact at all (Miuix is not Material), and the core icon subset is not stable across
 * Compose releases — `KeyboardArrowRight`, for instance, is missing from it. These paths are 24x24,
 * stroked where it makes sense, and tinted through `ColorFilter` by [MiuixIcon].
 */
object HgIcons {

    private fun strokeIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    private fun filledIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = block)
        }.build()

    val ArrowBack: ImageVector = strokeIcon("hg-arrow-back") {
        moveTo(15f, 5f)
        lineTo(8f, 12f)
        lineTo(15f, 19f)
    }

    val ChevronRight: ImageVector = strokeIcon("hg-chevron-right") {
        moveTo(9.5f, 6f)
        lineTo(15.5f, 12f)
        lineTo(9.5f, 18f)
    }

    val ChevronDown: ImageVector = strokeIcon("hg-chevron-down") {
        moveTo(6f, 9.5f)
        lineTo(12f, 15.5f)
        lineTo(18f, 9.5f)
    }

    val Check: ImageVector = strokeIcon("hg-check") {
        moveTo(5f, 13f)
        lineTo(10f, 18f)
        lineTo(19f, 6f)
    }

    val Close: ImageVector = strokeIcon("hg-close") {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }

    val Warning: ImageVector = strokeIcon("hg-warning") {
        moveTo(12f, 4f)
        lineTo(21f, 19.5f)
        lineTo(3f, 19.5f)
        close()
        moveTo(12f, 9.5f)
        lineTo(12f, 14f)
        moveTo(12f, 16.6f)
        lineTo(12f, 16.7f)
    }

    val Home: ImageVector = filledIcon("hg-home") {
        moveTo(12f, 3f)
        lineTo(21f, 11f)
        lineTo(18.5f, 11f)
        lineTo(18.5f, 20f)
        lineTo(13.5f, 20f)
        lineTo(13.5f, 14f)
        lineTo(10.5f, 14f)
        lineTo(10.5f, 20f)
        lineTo(5.5f, 20f)
        lineTo(5.5f, 11f)
        lineTo(3f, 11f)
        close()
    }

    val Apps: ImageVector = filledIcon("hg-apps") {
        moveTo(4f, 4f); lineTo(10.5f, 4f); lineTo(10.5f, 10.5f); lineTo(4f, 10.5f); close()
        moveTo(13.5f, 4f); lineTo(20f, 4f); lineTo(20f, 10.5f); lineTo(13.5f, 10.5f); close()
        moveTo(4f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 20f); lineTo(4f, 20f); close()
        moveTo(13.5f, 13.5f); lineTo(20f, 13.5f); lineTo(20f, 20f); lineTo(13.5f, 20f); close()
    }

    val Events: ImageVector = filledIcon("hg-events") {
        moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 8f); lineTo(4f, 8f); close()
        moveTo(4f, 11f); lineTo(20f, 11f); lineTo(20f, 13f); lineTo(4f, 13f); close()
        moveTo(4f, 16f); lineTo(15f, 16f); lineTo(15f, 18f); lineTo(4f, 18f); close()
    }

    val Diagnostics: ImageVector = strokeIcon("hg-diagnostics") {
        moveTo(3f, 12f)
        lineTo(8f, 12f)
        lineTo(10f, 6.5f)
        lineTo(13.5f, 17.5f)
        lineTo(16f, 12f)
        lineTo(21f, 12f)
    }

    val Shield: ImageVector = strokeIcon("hg-shield") {
        moveTo(12f, 3.5f)
        lineTo(19.5f, 6.5f)
        lineTo(19.5f, 12f)
        lineTo(12f, 20.5f)
        lineTo(4.5f, 12f)
        lineTo(4.5f, 6.5f)
        close()
    }
}
