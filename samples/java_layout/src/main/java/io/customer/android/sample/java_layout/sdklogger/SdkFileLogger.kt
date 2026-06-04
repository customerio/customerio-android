package io.customer.android.sample.java_layout.sdklogger

import android.content.Context
import android.util.Log
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Testing-only: mirrors every SDK log line to a file in the app's external files dir.
 *
 * Output: /sdcard/Android/data/<package>/files/cio-logs-<yyyyMMdd-HHmmss>.log
 * Pull:   adb pull /sdcard/Android/data/io.customer.android.sample.java_layout/files/
 *
 * setLogDispatcher REPLACES the default logcat output, so we re-emit each line to
 * logcat ourselves to keep both paths active.
 */
class SdkFileLogger(context: Context) {

    private val file: File = run {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val timestamp = FILE_TIMESTAMP_FORMAT.format(Date())
        File(dir, "cio-logs-$timestamp.log")
    }

    private val lock = Any()

    fun install() {
        SDKComponent.logger.setLogDispatcher { level, message ->
            when (level) {
                CioLogLevel.ERROR -> Log.e(TAG, message)
                CioLogLevel.INFO -> Log.i(TAG, message)
                CioLogLevel.DEBUG -> Log.d(TAG, message)
                CioLogLevel.NONE -> {}
            }
            synchronized(lock) {
                try {
                    file.appendText("${LINE_TIMESTAMP_FORMAT.format(Date())} $level $message\n")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write CIO log file", e)
                }
            }
        }
        Log.d(TAG, "SDK logs mirroring to ${file.absolutePath}")
    }

    companion object {
        private const val TAG = "[CIO]"
        private val FILE_TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        private val LINE_TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
