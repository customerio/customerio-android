package io.customer.messaginginbox

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.min

/**
 * Renders a pre-built branding bell glyph ([InboxBellSvg.BellArt]) — the workspace's configured
 * `patterns.inbox.floatingIcon.svg`, parsed once into a combined even-odd path — scaled-to-fit the
 * target size (aspect preserved) and filled with [tint].
 *
 * The art is built (and validated) up front by [InboxBellSvg.buildArt]; callers pass the result only
 * when non-null and fall back to the bundled drawable otherwise, so a malformed SVG never reaches
 * here. Only `<path d>` geometry + `viewBox` are honored (presentation attributes are ignored — the
 * bell is a single tinted silhouette).
 */
@Composable
internal fun InboxBellIcon(
    art: InboxBellSvg.BellArt,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val scale = min(size.width / art.width, size.height / art.height)
        // Center the scaled artwork in the target rect.
        val left = (size.width - art.width * scale) / 2f
        val top = (size.height - art.height * scale) / 2f
        translate(left = left, top = top) {
            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero) {
                // Shift the viewBox origin to (0,0) before scaling so a non-zero minX/minY viewBox fits.
                translate(left = -art.minX, top = -art.minY) {
                    drawPath(path = art.path, color = tint)
                }
            }
        }
    }
}

/**
 * Branding-SVG bell parsing for [InboxBellIcon]. [parse] is a pure (non-Compose) regex extraction of
 * the `viewBox` + each `<path>`'s `d`, unit-testable on the JVM. [buildArt] turns that into a
 * ready-to-draw [BellArt] using Compose's built-in [PathParser] (no third-party dep — unlike iOS,
 * which vendors a parser); it is crash-safe (see below) and its result is `remember`-cached by the
 * caller so the SVG is parsed once, not per frame.
 */
internal object InboxBellSvg {
    /** A built, ready-to-draw bell glyph: the combined even-odd [Path] plus its source viewBox. */
    data class BellArt(
        val path: Path,
        val minX: Float,
        val minY: Float,
        val width: Float,
        val height: Float
    )

    /** Parsed geometry: viewBox origin/size + the `d` string of each `<path>`. */
    data class Parsed(
        val minX: Float,
        val minY: Float,
        val width: Float,
        val height: Float,
        val pathData: List<String>
    )

    private val VIEW_BOX = Regex("""viewBox\s*=\s*["']\s*([^"']+?)\s*["']""", RegexOption.IGNORE_CASE)
    private val PATH_D = Regex("""<path\b[^>]*?\bd\s*=\s*["']([^"']+)["']""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * Parse [svg], or return null when there is nothing renderable (no `<path d>`). A missing
     * `viewBox` defaults to a 24×24 box so a bare `<svg><path/></svg>` still fits.
     */
    fun parse(svg: String): Parsed? {
        val pathData = PATH_D.findAll(svg).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        if (pathData.isEmpty()) return null

        val box = VIEW_BOX.find(svg)?.groupValues?.get(1)
            ?.split(Regex("[\\s,]+"))
            ?.mapNotNull { it.toFloatOrNull() }
        return if (box != null && box.size == 4 && box[2] > 0f && box[3] > 0f) {
            Parsed(minX = box[0], minY = box[1], width = box[2], height = box[3], pathData = pathData)
        } else {
            Parsed(minX = 0f, minY = 0f, width = 24f, height = 24f, pathData = pathData)
        }
    }

    /**
     * Build a ready-to-draw [BellArt] from raw [svg], or null when it cannot be rendered (no
     * `<path d>`, or the path data is malformed). [PathParser.parsePathString] THROWS
     * `IllegalArgumentException` on invalid path commands (e.g. `"M0 0 X1 1"`), so the build is
     * guarded — a malformed branding SVG falls back to the bundled bell instead of crashing.
     */
    fun buildArt(svg: String): BellArt? {
        val parsed = parse(svg) ?: return null
        return try {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                parsed.pathData.forEach { d -> addPath(PathParser().parsePathString(d).toPath()) }
            }
            if (path.isEmpty) null else BellArt(path, parsed.minX, parsed.minY, parsed.width, parsed.height)
        } catch (ex: Exception) {
            null
        }
    }
}
