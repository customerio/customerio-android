package io.customer.geofence.polygon

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.UUID

internal fun interface PolygonBootSessionProvider {
    fun currentSessionId(): String
}

internal class AndroidPolygonBootSessionProvider(
    private val context: Context
) : PolygonBootSessionProvider {
    override fun currentSessionId(): String {
        val bootCount = runCatching {
            Settings.Global.getString(context.contentResolver, BOOT_COUNT_SETTING)
        }.getOrNull()?.takeIf(String::isNotBlank)
        if (bootCount != null) return "count:$bootCount"

        val kernelBootId = runCatching {
            File(KERNEL_BOOT_ID_PATH).readText().trim()
        }.getOrNull()?.takeIf(String::isNotBlank)
        if (kernelBootId != null) return "kernel:$kernelBootId"

        // Rejecting same-boot process-death recovery beats accepting a fix from an unverifiable boot.
        return "process:$PROCESS_SESSION_ID"
    }

    private companion object {
        const val BOOT_COUNT_SETTING = "boot_count"
        const val KERNEL_BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
        val PROCESS_SESSION_ID: String = UUID.randomUUID().toString()
    }
}
