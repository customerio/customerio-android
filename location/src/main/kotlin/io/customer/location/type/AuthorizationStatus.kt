package io.customer.location.type

/**
 * Authorization status for location access.
 * Maps Android runtime permission states to SDK-level values
 * without depending on Android framework classes.
 *
 * Note: On Android, [DENIED] covers both "never asked" and "explicitly denied"
 * because [android.content.pm.PackageManager.checkPermission] cannot distinguish
 * between the two without an Activity context.
 */
internal enum class AuthorizationStatus {
    /** Permission not granted (either never asked or explicitly denied). */
    DENIED,

    /** Foreground-only location access granted. */
    AUTHORIZED_FOREGROUND,

    /** Background + foreground location access granted. */
    AUTHORIZED_BACKGROUND;

    /** Whether the app is authorized to use location (foreground or background). */
    val isAuthorized: Boolean
        get() = this == AUTHORIZED_FOREGROUND || this == AUTHORIZED_BACKGROUND
}
