package io.customer.messagingpush.livenotification.template

import android.graphics.Bitmap
import android.graphics.Color
import io.customer.messagingpush.livenotification.LiveNotificationAsset
import io.customer.messagingpush.testutils.core.IntegrationTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.amshove.kluent.shouldNotBeNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for [TemplateAssets.toBitmap].
 *
 * Branding hands the SDK a strongly-typed [LiveNotificationAsset] for the color
 * large-icon slot; these tests exercise resolution and the remote image cache.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class TemplateAssetsTest : IntegrationTest() {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun toBitmap_drawableAsset_rendersBitmap() {
        val asset = LiveNotificationAsset.Drawable(android.R.drawable.ic_dialog_info)

        val result = TemplateAssets.toBitmap(contextMock, asset)

        result.shouldNotBeNull()
    }

    @Test
    fun toBitmap_bytesAsset_decodesBitmap() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)
        bitmap.recycle()
        val asset = LiveNotificationAsset.Bytes(encoded.toByteArray())

        val result = TemplateAssets.toBitmap(contextMock, asset)

        result.shouldNotBeNull()
    }

    @Test
    fun toBitmap_remoteAsset_cachesScaledBitmap() {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        val imageFile = temporaryFolder.newFile("logo.png").apply { writeBytes(output.toByteArray()) }
        val asset = LiveNotificationAsset.RemoteUrl(imageFile.toURI().toURL().toString())

        val first = TemplateAssets.toBitmap(contextMock, asset)
        first.shouldNotBeNull()
        val maxSize = (64 * contextMock.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        assertTrue(first.width <= maxSize && first.height <= maxSize)

        imageFile.delete()
        val cached = TemplateAssets.toBitmap(contextMock, asset)
        cached.shouldNotBeNull()
        assertTrue(cached.width <= maxSize && cached.height <= maxSize)
    }

    @Test
    fun toBitmap_whenRemoteLogoIsWide_thenCachesSquareIconWithoutShrinkingShortSide() {
        val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        val imageFile = temporaryFolder.newFile("wide-logo.png").apply { writeBytes(output.toByteArray()) }
        val asset = LiveNotificationAsset.RemoteUrl(imageFile.toURI().toURL().toString())
        val targetSize = (64 * contextMock.resources.displayMetrics.density).toInt().coerceAtLeast(1)

        val first = TemplateAssets.toBitmap(contextMock, asset)
        first.shouldNotBeNull()
        assertEquals(targetSize, first.width)
        assertEquals(targetSize, first.height)

        imageFile.delete()
        val cached = TemplateAssets.toBitmap(contextMock, asset)
        cached.shouldNotBeNull()
        assertEquals(targetSize, cached.width)
        assertEquals(targetSize, cached.height)
    }

    @Test
    fun toBitmap_whenOldCacheContainsFittedLogo_thenDownloadsSquareAndRemovesOldEntry() {
        val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, encoded)
        source.recycle()
        val imageFile = temporaryFolder.newFile("new-logo.png").apply { writeBytes(encoded.toByteArray()) }
        val url = imageFile.toURI().toURL().toString()
        val size = (64 * contextMock.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val oldKey = MessageDigest.getInstance("SHA-256").digest("v2:$size:$url".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cacheDir = File(contextMock.cacheDir, "cio_live_notification_assets").apply { mkdirs() }
        val oldCache = File(cacheDir, oldKey)
        val fitted = Bitmap.createBitmap(size, size / 2, Bitmap.Config.ARGB_8888)
        oldCache.outputStream().use { fitted.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fitted.recycle()

        val result = TemplateAssets.toBitmap(contextMock, LiveNotificationAsset.RemoteUrl(url))

        result.shouldNotBeNull()
        assertEquals(size, result.width)
        assertEquals(size, result.height)
        org.junit.Assert.assertFalse(oldCache.exists())
    }
}
