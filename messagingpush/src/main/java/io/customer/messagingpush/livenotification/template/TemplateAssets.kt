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
import kotlin.math.roundToInt

/**
 * Resolves a strongly-typed [LiveNotificationAsset] to a [Bitmap] for the
 * notification's color large-icon slot.
 */
internal object TemplateAssets {

    private const val URL_CACHE_DIR = "cio_live_notification_assets"
    private const val MAX_REMOTE_ICON_DP = 64

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

    /** Downloads [url], caching the scaled bitmap on disk to avoid re-fetching. */
    private fun downloadCached(context: Context, url: String): Bitmap? {
        val cacheDir = File(context.cacheDir, URL_CACHE_DIR).apply { mkdirs() }
        val maxSize = (MAX_REMOTE_ICON_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val cacheFile = File(cacheDir, sha256("v3:$maxSize:$url"))
        if (cacheFile.exists()) {
            BitmapDownloader.decodeFile(cacheFile, maxSize, maxSize)?.let { return it }
        }
        val bitmap = BitmapDownloader.download(url, maxSize, maxSize, centerCrop = true) ?: return null
        try {
            val saved = writeCache(cacheFile, bitmap)
            if (saved) File(cacheDir, sha256(url)).delete()
        } catch (e: Exception) {
            SDKComponent.logger.debug("Failed to cache live notification image '$url': ${e.message}")
        }
        return bitmap
    }

    private fun writeCache(file: File, bitmap: Bitmap): Boolean {
        val temporary = File.createTempFile("cio_live_", ".tmp", file.parentFile)
        return try {
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } &&
                temporary.renameTo(file)
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
