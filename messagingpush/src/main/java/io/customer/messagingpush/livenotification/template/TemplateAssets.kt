package io.customer.messagingpush.livenotification.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import io.customer.messagingpush.livenotification.LiveNotificationAsset
import io.customer.messagingpush.util.BitmapDownloader
import io.customer.sdk.core.di.SDKComponent
import java.io.File
import java.security.MessageDigest

/**
 * Resolves a strongly-typed [LiveNotificationAsset] to a [Bitmap] for the
 * notification's color large-icon slot.
 */
internal object TemplateAssets {

    private const val URL_CACHE_DIR = "cio_live_notification_assets"

    fun toBitmap(context: Context, asset: LiveNotificationAsset): Bitmap? =
        try {
            when (asset) {
                is LiveNotificationAsset.Drawable -> drawableResToBitmap(context, asset.resId)
                is LiveNotificationAsset.Bytes -> BitmapFactory.decodeByteArray(asset.data, 0, asset.data.size)
                is LiveNotificationAsset.Resource ->
                    context.contentResolver.openInputStream(asset.uri).use { stream ->
                        stream?.let { BitmapFactory.decodeStream(it) }
                    }
                is LiveNotificationAsset.RemoteUrl -> downloadCached(context, asset.url)
            }
        } catch (e: Exception) {
            SDKComponent.logger.error("Failed to load live notification asset: ${e.message}")
            null
        }

    fun drawableResToBitmap(context: Context, @DrawableRes res: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, res) ?: return null
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
        return try {
            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            SDKComponent.logger.error("Failed to convert drawable $res to bitmap: ${e.message}")
            null
        }
    }

    /** Downloads [url], caching the bytes on disk to avoid re-fetching. */
    private fun downloadCached(context: Context, url: String): Bitmap? {
        val cacheFile = File(File(context.cacheDir, URL_CACHE_DIR).apply { mkdirs() }, sha256(url))
        if (cacheFile.exists()) {
            BitmapFactory.decodeFile(cacheFile.path)?.let { return it }
        }
        val bitmap = BitmapDownloader.download(url) ?: return null
        try {
            cacheFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            SDKComponent.logger.debug("Failed to cache live notification image '$url': ${e.message}")
        }
        return bitmap
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
