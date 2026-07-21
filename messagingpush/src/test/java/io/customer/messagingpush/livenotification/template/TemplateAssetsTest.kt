package io.customer.messagingpush.livenotification.template

import io.customer.messagingpush.livenotification.LiveNotificationAsset
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [TemplateAssets.toBitmap].
 *
 * Branding hands the SDK a strongly-typed [LiveNotificationAsset] for the color
 * large-icon slot; these tests exercise that each supported source type resolves
 * to a bitmap. The remote-URL case is not covered here because it performs a
 * real network fetch via `BitmapDownloader`; the disk-cache path is exercised
 * separately by the handler-level tests.
 */
@RunWith(RobolectricTestRunner::class)
internal class TemplateAssetsTest : IntegrationTest() {

    @Test
    fun toBitmap_drawableAsset_rendersBitmap() {
        val asset = LiveNotificationAsset.Drawable(android.R.drawable.ic_dialog_info)

        val result = TemplateAssets.toBitmap(contextMock, asset)

        result.shouldNotBeNull()
    }

    @Test
    fun toBitmap_bytesAsset_decodesBitmap() {
        val asset = LiveNotificationAsset.Bytes(byteArrayOf(1, 2, 3, 4))

        val result = TemplateAssets.toBitmap(contextMock, asset)

        result.shouldNotBeNull()
    }
}
