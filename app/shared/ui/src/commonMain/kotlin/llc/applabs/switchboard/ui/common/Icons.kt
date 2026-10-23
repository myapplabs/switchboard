package llc.applabs.switchboard.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The few icons the app draws itself (24 dp, outlined; tinted by the Icon that shows them). */
object AppIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)

    /** An arrow pointing up: send. */
    val Send: ImageVector by lazy {
        icon("Send") { stroke { moveTo(12f, 19f); lineTo(12f, 5f); moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) } }
    }

    /** A paper clip: attach a file or photo. */
    val Attach: ImageVector by lazy {
        icon("Attach") {
            stroke {
                moveTo(20.5f, 11.5f); lineTo(12.4f, 19.6f)
                arcTo(5f, 5f, 0f, false, true, 5.3f, 12.5f); lineTo(13.4f, 4.4f)
                arcTo(3.3f, 3.3f, 0f, false, true, 18.1f, 9.1f); lineTo(10f, 17.2f)
                arcTo(1.65f, 1.65f, 0f, false, true, 7.7f, 14.9f); lineTo(15.2f, 7.4f)
            }
        }
    }

    /** A cross: remove. */
    val Close: ImageVector by lazy {
        icon("Close") { stroke { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    }

    /** A sheet with a folded corner: a file. */
    val File: ImageVector by lazy {
        icon("File") {
            stroke {
                moveTo(14f, 3f); lineTo(6f, 3f); lineTo(6f, 21f); lineTo(18f, 21f); lineTo(18f, 7f); close()
                moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
            }
        }
    }

    /** A square: stop. */
    val Stop: ImageVector by lazy {
        icon("Stop") { path(fill = SolidColor(Color.Black)) { moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close() } }
    }
}
