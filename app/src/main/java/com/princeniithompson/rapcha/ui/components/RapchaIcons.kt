package com.princeniithompson.rapcha.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Shared custom vector icons for Rapcha.
 * Single-color vectors designed on a 24dp viewport so they adopt any Compose Icon tint cleanly.
 */
object RapchaIcons {

    private var _modules: ImageVector? = null

    /**
     * Shared Modules icon: a magnifying glass tilted to the left (thick round lens + short thick
     * rounded-end handle toward bottom-right) paired with three tiny four-point sparkles near
     * the top-right of the lens. Clean at both 16dp and 24dp.
     */
    val Modules: ImageVector
        get() {
            val cached = _modules
            if (cached != null) return cached

            val built = ImageVector.Builder(
                name = "RapchaIcons.Modules",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            ).apply {
                val ink = SolidColor(Color.Black)

                // Magnifying glass lens: thick ring positioned upper-left
                val cx = 9.6f
                val cy = 10.6f
                val r = 5.8f
                path(
                    stroke = ink,
                    strokeLineWidth = 2.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(cx - r, cy)
                    arcToRelative(
                        a = r,
                        b = r,
                        theta = 0f,
                        isMoreThanHalf = true,
                        isPositiveArc = true,
                        dx1 = 2f * r,
                        dy1 = 0f
                    )
                    arcToRelative(
                        a = r,
                        b = r,
                        theta = 0f,
                        isMoreThanHalf = true,
                        isPositiveArc = true,
                        dx1 = -2f * r,
                        dy1 = 0f
                    )
                    close()
                }

                // Short thick handle extending to bottom-right with rounded ends
                path(
                    stroke = ink,
                    strokeLineWidth = 2.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(13.8f, 14.8f)
                    lineTo(19.4f, 20.4f)
                }

                // Three very tiny four-point Gemini-style sparkles near the top-right of the lens
                path(
                    fill = ink,
                    pathFillType = PathFillType.NonZero
                ) {
                    // 1. Small primary four-point sparkle
                    addFourPointSparkle(cx = 19.2f, cy = 5.8f, radius = 2.3f, waist = 0.45f)
                    // 2. Tinier sparkle above top-right rim
                    addFourPointSparkle(cx = 15.2f, cy = 2.7f, radius = 1.35f, waist = 0.28f)
                    // 3. Tinier sparkle below-right of the main sparkle
                    addFourPointSparkle(cx = 20.8f, cy = 10.2f, radius = 1.25f, waist = 0.26f)
                }
            }.build()

            _modules = built
            return built
        }

    private fun PathBuilder.addFourPointSparkle(
        cx: Float,
        cy: Float,
        radius: Float,
        waist: Float
    ) {
        moveTo(cx, cy - radius)
        quadTo(cx + waist, cy - waist, cx + radius, cy)
        quadTo(cx + waist, cy + waist, cx, cy + radius)
        quadTo(cx - waist, cy + waist, cx - radius, cy)
        quadTo(cx - waist, cy - waist, cx, cy - radius)
        close()
    }
}
