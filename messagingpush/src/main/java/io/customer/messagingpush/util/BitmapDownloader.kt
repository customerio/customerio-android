package io.customer.messagingpush.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.customer.sdk.core.di.SDKComponent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal object BitmapDownloader {

    fun download(imageUrl: String, maxWidth: Int, maxHeight: Int): Bitmap? = runBlocking {
        withContext(Dispatchers.IO) {
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_TRANSFER_DURATION_MS)
                val connection = URL(imageUrl).openConnection().apply {
                    connectTimeout = MAX_TRANSFER_DURATION_MS.toInt()
                    readTimeout = MAX_TRANSFER_DURATION_MS.toInt()
                }
                if (connection.contentLength > MAX_DOWNLOAD_BYTES) {
                    throw IOException("Image exceeds the download limit")
                }
                val bytes = connection.getInputStream().use { input ->
                    readBounded(input, connection, deadline)
                }
                remainingMillis(deadline)
                decodeSampled(bytes, maxWidth, maxHeight)
            } catch (e: Exception) {
                SDKComponent.logger.error("Failed to download bitmap from '$imageUrl': ${e.message}")
                null
            }
        }
    }

    fun decodeFile(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (!validDimensions(bounds)) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
        }
        return BitmapFactory.decodeFile(file.path, options)?.let { scaleToFit(it, maxWidth, maxHeight) }
    }

    internal fun decodeSampled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!validDimensions(bounds)) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?.let { scaleToFit(it, maxWidth, maxHeight) }
    }

    internal fun calculateInSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        require(maxWidth > 0 && maxHeight > 0)
        var sampleSize = 1
        while (
            width.toLong() >= 2L * sampleSize * maxWidth ||
            height.toLong() >= 2L * sampleSize * maxHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun scaleToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = minOf(
            maxWidth.toFloat() / bitmap.width,
            maxHeight.toFloat() / bitmap.height,
            1f
        )
        if (scale >= 1f) return bitmap
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        bitmap.recycle()
        return scaled
    }

    private fun validDimensions(bounds: BitmapFactory.Options): Boolean =
        bounds.outWidth in 1..MAX_SOURCE_DIMENSION_PX &&
            bounds.outHeight in 1..MAX_SOURCE_DIMENSION_PX &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS

    private fun readBounded(input: InputStream, connection: URLConnection, deadline: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            connection.readTimeout = remainingMillis(deadline)
            val count = input.read(chunk)
            if (count == -1) break
            if (output.size() + count > MAX_DOWNLOAD_BYTES) {
                throw IOException("Image exceeds the download limit")
            }
            output.write(chunk, 0, count)
        }
        return output.toByteArray()
    }

    private fun remainingMillis(deadline: Long): Int {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining <= 0) throw SocketTimeoutException("Image download exceeded its time limit")
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private const val MAX_TRANSFER_DURATION_MS = 8_000L
    private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024
    private const val MAX_SOURCE_DIMENSION_PX = 65_536
    private const val MAX_SOURCE_PIXELS = 50_000_000L
}
