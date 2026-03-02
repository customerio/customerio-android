package io.customer.location.type

/**
 * Authorization status for location access.
 * Maps Android runtime permission states to SDK-level values
 * without depending on Android framework classes.
 */
internal enum class AuthorizationStatus {
    /**
     * Permission has not been requested yet.
     *
     * Note: On Android, [checkSelfPermission] cannot distinguish between
     * "never asked" and "explicitly denied" without an Activity context.
     * This value exists for API completeness but is not currently returned
     * by [FusedLocationProvider].
     */
    NOT_DETERMINED,

    /** Permission explicitly denied by the user. */
    DENIED,

    /** Foreground-only location access granted. */
    AUTHORIZED_FOREGROUND,

    /** Background + foreground location access granted. */
    AUTHORIZED_BACKGROUND;

    /** Whether the app is authorized to use location (foreground or background). */
    val isAuthorized: Boolean
        get() = this == AUTHORIZED_FOREGROUND || this == AUTHORIZED_BACKGROUND
}
