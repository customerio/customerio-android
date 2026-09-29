package io.customer.messagingpush.util

import android.graphics.Bitmap
import android.graphics.Color
import io.customer.messagingpush.testutils.core.IntegrationTest
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

@RunWith(RobolectricTestRunner::class)
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
