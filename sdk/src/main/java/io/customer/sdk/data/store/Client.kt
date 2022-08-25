package io.customer.sdk.data.store

/**
 * Sealed class to hold information about the SDK wrapper and package that the
 * client app is using.
 *
 * @property source name of the client to append with user-agent.
 * @property sdkVersion version of the SDK used.
 */
sealed class Client(
    val source: String,
    val sdkVersion: String
) {
    override fun toString(): String = "$source Client/$sdkVersion"

    /**
     * Simpler class for Android clients.
     */
    class Android(sdkVersion: String) : Client(source = "Android", sdkVersion = sdkVersion)

    /**
     * Simpler class for ReactNative clients.
     */
    class ReactNative(sdkVersion: String) : Client(source = "ReactNative", sdkVersion = sdkVersion)

    /**
     * Simpler class for Expo clients.
     */
    class Expo(sdkVersion: String) : Client(source = "Expo", sdkVersion = sdkVersion)

    /**
     * Other class to allow adding custom sources for clients that are not
     * supported above.
     * <p/>
     * Use this only if the client platform is not available in the above list.
     */
    class Other(
        source: String,
        sdkVersion: String
    ) : Client(source = source, sdkVersion = sdkVersion)
}
