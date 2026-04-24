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
                URL(imageUrl).openStream().use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                SDKComponent.logger.error("Failed to download bitmap from '$imageUrl': ${e.message}")
                null
            }
        }
    }
}
