package io.customer.messagingpush.livenotification.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import io.customer.messagingpush.di.pushModuleConfig
import io.customer.messagingpush.extensions.getDrawableByName
import io.customer.messagingpush.livenotification.LiveNotificationAsset
import io.customer.messagingpush.util.BitmapDownloader
import io.customer.sdk.core.di.SDKComponent
import java.io.File
import java.security.MessageDigest

/**
 * Resolves a live-notification image key to a [Bitmap], in priority order:
 *
 *  1. **Remote URL** (`http`/`https`) — downloaded via [BitmapDownloader] and
 *     cached on disk so re-renders of the same activity don't re-fetch.
 *  2. **Registered asset** — a key declared by the host app via
 *     `registerLiveNotificationAsset` (drawable / URI / raw bytes).
 *  3. **Bundled drawable** — a `R.drawable.*` entry looked up by name. Keys are
 *     kebab-case in the spec (e.g. `delivery-warehouse`) but Android resource
 *     names only allow `[a-z0-9_]`, so hyphens are normalized to underscores.
 *
 * The notification **small icon** is not a bitmap slot (Android requires a
 * drawable resource there); [resolveDrawable] keeps the drawable-name-only path
 * for that case.
 */
internal object TemplateAssets {

    private const val URL_CACHE_DIR = "cio_live_notification_assets"

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
        if (key.isNullOrBlank()) return null

        // 1. Remote URL.
        if (isRemoteUrl(key)) {
            return downloadCached(context, key)
        }

        // 2. Host-registered asset.
        SDKComponent.pushModuleConfig.liveNotificationAssets[key]?.let { asset ->
            return loadRegisteredAsset(context, key, asset)
        }

        // 3. Bundled drawable by name.
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

    private fun isRemoteUrl(key: String): Boolean =
        key.startsWith("http://", ignoreCase = true) || key.startsWith("https://", ignoreCase = true)

    private fun loadRegisteredAsset(context: Context, key: String, asset: LiveNotificationAsset): Bitmap? =
        try {
            when (asset) {
                is LiveNotificationAsset.Drawable -> drawableResToBitmap(context, asset.resId)
                is LiveNotificationAsset.Bytes -> BitmapFactory.decodeByteArray(asset.data, 0, asset.data.size)
                is LiveNotificationAsset.Resource ->
                    context.contentResolver.openInputStream(asset.uri).use { stream ->
                        stream?.let { BitmapFactory.decodeStream(it) }
                    }
            }
        } catch (e: Exception) {
            SDKComponent.logger.error("Failed to load registered live notification asset '$key': ${e.message}")
            null
        }

    /**
     * Downloads [url] (caching the bytes on disk under the app cache dir) so the
     * same image isn't re-fetched on every in-place update of an activity.
     */
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
