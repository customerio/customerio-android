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
 * `patterns.inbox.floatingIcon.svg`, parsed once into one path per `<path>` (each with its own fill rule) — scaled-to-fit the
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
                    // Fill each <path> INDEPENDENTLY (each with its own fill rule) so overlapping paths don't flip
                    // each other's winding parity (MBL-2123). Uniform tint → the union reads as one glyph.
                    art.paths.forEach { path -> drawPath(path = path, color = tint) }
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
    /**
     * A built, ready-to-draw bell glyph: one [Path] per source `<path>` element (each with its own fill rule, filled
     * independently — see [InboxBellIcon]) plus its source viewBox.
     */
    data class BellArt(
        val paths: List<Path>,
        val minX: Float,
        val minY: Float,
        val width: Float,
        val height: Float
    )

    /** One `<path>` element: its `d` geometry + whether it fills even-odd (else nonzero). */
    data class PathSpec(val d: String, val evenOdd: Boolean)

    /** Parsed geometry: viewBox origin/size + the per-`<path>` specs. */
    data class Parsed(
        val minX: Float,
        val minY: Float,
        val width: Float,
        val height: Float,
        val paths: List<PathSpec>
    )

    private val VIEW_BOX = Regex("""viewBox\s*=\s*["']\s*([^"']+?)\s*["']""", RegexOption.IGNORE_CASE)
    private val PATH_TAG = Regex("""<path\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SVG_TAG = Regex("""<svg\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val D_ATTR = Regex("""\bd\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    // `fill-rule="evenodd"` (attribute) or `fill-rule: evenodd` (CSS style), either rule, any quoting.
    private val FILL_RULE = Regex("""fill-rule\s*[=:]\s*["']?\s*(evenodd|nonzero)""", RegexOption.IGNORE_CASE)

    /**
     * Parse [svg], or return null when there is nothing renderable (no `<path d>`). A missing
     * `viewBox` defaults to a 24×24 box so a bare `<svg><path/></svg>` still fits. Each `<path>`
     * carries its OWN fill rule — the path's declared `fill-rule`, else the root `<svg>`'s, else
     * nonzero (the SVG/CSS default) — matching how a browser fills each path (MBL-2123), and matching
     * iOS which likewise honors the declared rule per path.
     */
    fun parse(svg: String): Parsed? {
        val rootEvenOdd = SVG_TAG.find(svg)?.value?.let { fillRuleEvenOdd(it) }
        val paths = PATH_TAG.findAll(svg).mapNotNull { match ->
            val tag = match.value
            val d = D_ATTR.find(tag)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            PathSpec(d = d, evenOdd = fillRuleEvenOdd(tag) ?: rootEvenOdd ?: false)
        }.toList()
        if (paths.isEmpty()) return null

        val box = VIEW_BOX.find(svg)?.groupValues?.get(1)
            ?.split(Regex("[\\s,]+"))
            ?.mapNotNull { it.toFloatOrNull() }
        return if (box != null && box.size == 4 && box[2] > 0f && box[3] > 0f) {
            Parsed(minX = box[0], minY = box[1], width = box[2], height = box[3], paths = paths)
        } else {
            Parsed(minX = 0f, minY = 0f, width = 24f, height = 24f, paths = paths)
        }
    }

    /** true if [tag] declares `fill-rule` evenodd, false if nonzero, null if it declares neither. */
    private fun fillRuleEvenOdd(tag: String): Boolean? =
        FILL_RULE.find(tag)?.groupValues?.get(1)?.equals("evenodd", ignoreCase = true)

    /**
     * Build a ready-to-draw [BellArt] from raw [svg], or null when it cannot be rendered (no
     * `<path d>`, or the path data is malformed). [PathParser.parsePathString] THROWS
     * `IllegalArgumentException` on invalid path commands (e.g. `"M0 0 X1 1"`), so the build is
     * guarded — a malformed branding SVG falls back to the bundled bell instead of crashing.
     */
    fun buildArt(svg: String): BellArt? {
        val parsed = parse(svg) ?: return null
        return try {
            // Build each <path> as its OWN path with its declared fill rule (nonzero default), filled
            // independently by InboxBellIcon. Merging them into one path + a single fill rule flips
            // parity where paths overlap, producing a malformed glyph (MBL-2123); browsers fill each
            // <path> on its own with that path's own rule.
            val paths = parsed.paths.mapNotNull { spec ->
                PathParser().parsePathString(spec.d).toPath()
                    .apply { fillType = if (spec.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero }
                    .takeIf { !it.isEmpty }
            }
            if (paths.isEmpty()) null else BellArt(paths, parsed.minX, parsed.minY, parsed.width, parsed.height)
        } catch (ex: Exception) {
            null
        }
    }
}
