package io.customer.messagingpush.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.customer.sdk.core.di.SDKComponent
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal object BitmapDownloader {

    fun download(imageUrl: String): Bitmap? = runBlocking {
        withContext(Dispatchers.IO) {
            try {
                // Bound connect + read so a stalled fetch can't block the caller
                // (e.g. the FCM delivery thread) indefinitely.
                val connection = URL(imageUrl).openConnection().apply {
                    connectTimeout = DOWNLOAD_TIMEOUT_MS
                    readTimeout = DOWNLOAD_TIMEOUT_MS
                }
                connection.getInputStream().use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                SDKComponent.logger.error("Failed to download bitmap from '$imageUrl': ${e.message}")
                null
            }
        }
    }

    private const val DOWNLOAD_TIMEOUT_MS = 10_000
}
