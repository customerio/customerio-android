package io.customer.messagingpush.util

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class BitmapDownloaderTest : IntegrationTest() {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun download_whenCompressedImageHasLargeDimensions_thenDecodesToTargetSize() {
        val image = compressedImage()
        val file = temporaryFolder.newFile("large.png").apply { writeBytes(image) }

        val result = BitmapDownloader.download(file.toURI().toURL().toString(), 128, 128)

        assertEquals(128, result?.width)
        assertEquals(128, result?.height)
    }

    @Test
    fun decodeSampled_whenSourceIsSlightlyLarger_thenKeepsEnoughDetailToScaleDown() {
        val result = BitmapDownloader.decodeSampled(compressedImage(512, 512), 192, 192)

        assertEquals(192, result?.width)
        assertEquals(192, result?.height)
    }

    @Test
    fun decodeSampled_whenImageIsWide_thenPreservesAspectRatio() {
        val result = BitmapDownloader.decodeSampled(compressedImage(1200, 600), 1080, 1920)

        assertEquals(1080, result?.width)
        assertEquals(540, result?.height)
    }

    @Test
    fun decodeSampled_whenPanoramicLogoIsCenterCropped_thenKeepsSquareDetailWithinTarget() {
        // Robolectric's region decoder models output dimensions but does not decode pixels.
        val result = BitmapDownloader.decodeSampled(compressedImage(4096, 64), 64, 64, centerCrop = true)

        assertEquals(64, result?.width)
        assertEquals(64, result?.height)
    }

    @Test
    fun squareIcon_whenImageIsWide_thenKeepsCenterPixelsAndSourceBitmap() {
        val source = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        Canvas(source).drawRect(64f, 0f, 192f, 128f, Paint().apply { color = Color.BLUE })

        val result = BitmapDownloader.squareIcon(source, 64)

        assertEquals(64, result.width)
        assertEquals(64, result.height)
        assertEquals(Color.BLUE, result.getPixel(0, 0))
        assertEquals(Color.BLUE, result.getPixel(63, 63))
        org.junit.Assert.assertFalse(source.isRecycled)
    }

    @Test
    fun decodeSampled_whenLogoIsTall_thenCenterCropsToSquare() {
        val result = BitmapDownloader.decodeSampled(compressedImage(128, 1024), 64, 64, centerCrop = true)

        assertEquals(64, result?.width)
        assertEquals(64, result?.height)
    }

    @Suppress("DEPRECATION")
    @Test
    fun decodeSampled_whenRegionDecoderRejectsFormat_thenKeepsShortSideDetailInFallback() {
        mockkStatic(BitmapRegionDecoder::class)
        try {
            every { BitmapRegionDecoder.newInstance(any<ByteArray>(), any(), any(), any()) } throws IOException("Unsupported format")

            val result = BitmapDownloader.decodeSampled(compressedImage(1024, 256), 192, 192, centerCrop = true)

            assertEquals(192, result?.width)
            assertEquals(192, result?.height)
            assertEquals(Color.BLUE, result?.getPixel(0, 0))
        } finally {
            unmockkStatic(BitmapRegionDecoder::class)
        }
    }

    @Test
    fun calculateSquareSampleSize_whenImageHasExtremeAspectRatio_thenBoundsDecodedPixels() {
        val sample = BitmapDownloader.calculateSquareSampleSize(50_000, 1000, 192)
        val width = (50_000L + sample - 1) / sample
        val height = (1000L + sample - 1) / sample

        org.junit.Assert.assertTrue(width * height <= 1_048_576)
    }

    @Test
    fun decodeFile_whenCachedImageIsFullSize_thenDecodesToTargetSize() {
        val file = temporaryFolder.newFile("cached.png").apply { writeBytes(compressedImage()) }

        val result = BitmapDownloader.decodeFile(file, 64, 64)

        assertEquals(64, result?.width)
        assertEquals(64, result?.height)
    }

    @Test
    fun download_whenEncodedImageExceedsLimit_thenSkipsImage() {
        val file = temporaryFolder.newFile("oversized.png").apply {
            writeBytes(ByteArray(5 * 1024 * 1024 + 1))
        }

        val result = BitmapDownloader.download(file.toURI().toURL().toString(), 256, 256)

        assertNull(result)
    }

    @Test
    fun download_whenChunkedResponseExceedsLimit_thenSkipsImage() {
        val server = ServerSocket(0)
        val writer = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    val chunk = ByteArray(8 * 1024)
                    repeat(641) {
                        output.write("2000\r\n".toByteArray())
                        output.write(chunk)
                        output.write("\r\n".toByteArray())
                    }
                    output.write("0\r\n\r\n".toByteArray())
                }
            } catch (_: IOException) {
                // The client closes the connection after rejecting the oversized response.
            }
        }

        try {
            val result = BitmapDownloader.download(
                "http://127.0.0.1:${server.localPort}/image",
                256,
                256
            )

            assertNull(result)
        } finally {
            server.close()
            writer.join(1_000)
        }
    }

    private fun compressedImage(width: Int = 1024, height: Int = 1024): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
