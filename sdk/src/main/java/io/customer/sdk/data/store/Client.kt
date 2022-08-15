package io.customer.sdk.data.store

import androidx.annotation.VisibleForTesting

/**
 * Date class to hold information about the package client app is using.
 *
 * This class only holds info that can have multiple values e.g. source
 * package type (Android, ReactNative, etc.) and not the information that
 * cannot be change (e.g. the SDK version)
 *
 * @property source name of the client source to append with user-agent
 * @property sdkVersion version of the SDK used
 */
class Client private constructor(
    val source: String,
    val sdkVersion: String
) {
    override fun toString(): String = "$source Client/$sdkVersion"

    companion object {
        @VisibleForTesting
        internal const val SOURCE_ANDROID = "Android"

        @VisibleForTesting
        internal const val SOURCE_EXPO = "Expo"

        @VisibleForTesting
        internal const val SOURCE_REACT_NATIVE = "ReactNative"

        /**
         * Creates the right client based on name provided (case insensitive).
         * <p/>
         * Use this if you don't want to use direct method or want to use same
         * method from different SDK wrappers.
         *
         * @param source name of the source platform.
         * @param sdkVersion version of the SDK being used.
         * @return new instance of client against the provided data.
         */
        fun create(source: String, sdkVersion: String): Client = when {
            SOURCE_ANDROID.equals(source, ignoreCase = true) -> android(sdkVersion)
            SOURCE_EXPO.equals(source, ignoreCase = true) -> expo(sdkVersion)
            SOURCE_REACT_NATIVE.equals(source, ignoreCase = true) -> reactNative(sdkVersion)
            else -> newInstance(source = source, sdkVersion = sdkVersion)
        }

        /**
         * Enforces Android platform, helper method for convenience and
         * improved readability.
         */
        fun android(sdkVersion: String) = newInstance(
            source = SOURCE_ANDROID,
            sdkVersion = sdkVersion
        )

        /**
         * Enforces Expo platform, helper method for convenience and
         * improved readability.
         */
        fun expo(sdkVersion: String) = newInstance(
            source = SOURCE_EXPO,
            sdkVersion = sdkVersion
        )

        /**
         * Enforces ReactNative platform, helper method for convenience and
         * improved readability.
         */
        fun reactNative(sdkVersion: String) = newInstance(
            source = SOURCE_REACT_NATIVE,
            sdkVersion = sdkVersion
        )

        /**
         * Internal method to create client instance.
         */
        private fun newInstance(source: String, sdkVersion: String): Client = Client(
            source = source,
            sdkVersion = sdkVersion
        )
    }
}
