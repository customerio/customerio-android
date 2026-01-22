package io.customer.location.permission

/**
 * Represents the current state of location permissions for the app.
 */
enum class LocationPermissionStatus {
    /**
     * Permission has never been requested.
     * The app should prompt the user to grant permission.
     */
    NOT_DETERMINED,

    /**
     * User has explicitly denied location permission.
     * The app should guide user to settings to enable permission.
     */
    DENIED,

    /**
     * User has granted location permission.
     * Location can be accessed while the app is in foreground.
     */
    AUTHORIZED
}
