package io.customer.sdk.data.store

import android.content.Context
import io.customer.sdk.Version
import io.customer.sdk.core.extensions.applicationMetaData

/**
 * Represents the client information to append with user-agent.
 *
 * @property source name of the client to append with user-agent.
 * @property sdkVersion version of the SDK used.
 */
@Suppress("MemberVisibilityCanBePrivate")
class Client(
    val source: String,
    val sdkVersion: String
) {
    override fun toString(): String = "$source Client/$sdkVersion"

    companion object {
        private const val SOURCE_ANDROID = "Android"
        private const val META_DATA_USER_AGENT = "io.customer.sdk.android.core.USER_AGENT"
        private const val META_DATA_SDK_VERSION = "io.customer.sdk.android.core.SDK_VERSION"

        /**
         * Creates a new [Client] instance from the manifest meta-data.
         * If the user-agent or SDK version is not found, the default client is returned.
         * Default client is created with [SOURCE_ANDROID] and SDK version mentioned in [Version] class.
         *
         * @param context The context to retrieve the meta-data from.
         * @return The client instance created from the manifest meta-data.
         * If not found, the default client is returned.
         */
        fun fromManifest(context: Context): Client {
            // Retrieve user agent and SDK version from manifest
            val appMetaData = context.applicationMetaData()
            val userAgent = appMetaData?.getString(META_DATA_USER_AGENT)
            val sdkVersion = appMetaData?.getString(META_DATA_SDK_VERSION)

            // If either value is null or blank, return the default client
            return if (userAgent.isNullOrBlank() || sdkVersion.isNullOrBlank()) {
                Client(source = SOURCE_ANDROID, sdkVersion = Version.version)
            } else {
                Client(source = userAgent, sdkVersion = sdkVersion)
            }
        }
    }
}
