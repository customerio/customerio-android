package io.customer.messagingpush.livenotification.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.customer.messagingpush.extensions.getDrawableByName
import io.customer.sdk.core.di.SDKComponent

/**
 * Drawable lookup + bitmap conversion shared by every template.
 *
 * Templates carry image keys as kebab-case strings (e.g. `delivery-warehouse`)
 * but Android resource names only allow `[a-z0-9_]`. We normalize hyphens to
 * underscores before delegating to [Context.getDrawableByName], so spec values
 * resolve to the matching `R.drawable.*` entries the host app ships.
 */
internal object TemplateAssets {

    @DrawableRes
    fun resolveDrawable(context: Context, key: String?): Int? {
        if (key.isNullOrBlank()) return null
        val normalized = key.replace('-', '_')
        val resolved = context.getDrawableByName(normalized)
        if (resolved == null) {
            // The key was specified but no drawable matched. This is a configuration
            // mismatch (server pushing keys the host app hasn't bundled). The template
            // still renders — large-icon slot stays empty per "design for absence" —
            // but a warning surfaces so developers can diagnose missing assets.
            SDKComponent.logger.debug(
                "Live notification asset key '$key' did not resolve to a drawable; rendering without it."
            )
        }
        return resolved
    }

    fun resolveBitmap(context: Context, key: String?): Bitmap? {
        val res = resolveDrawable(context, key) ?: return null
        return drawableResToBitmap(context, res)
    }

    fun drawableResToBitmap(context: Context, @DrawableRes res: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, res) ?: return null
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            SDKComponent.logger.error("Failed to convert drawable $res to bitmap: ${e.message}")
            null
        }
    }
}
