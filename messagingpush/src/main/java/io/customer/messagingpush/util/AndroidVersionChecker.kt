package io.customer.messagingpush.util

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Utility class to check Android version.
 * This class is extracted to make it easier to mock in tests.
 */
internal class AndroidVersionChecker {

    /**
     * Checks if the device is running Android Oreo (API 26) or higher.
     *
     * @return true if the device is running Android Oreo or higher, false otherwise
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    fun isOreoOrHigher(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
