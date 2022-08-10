package io.customer.sdk.data.store

import java.util.*

/**
 * Date class to hold information about the package client app is using.
 *
 * This class only holds info that can have multiple values e.g. source
 * package type (Android, ReactNative, etc.) and not the information that
 * cannot be change (e.g. the SDK version)
 *
 * @property source name of the client source to append with user-agent
 * @property identifier name only used to identify client, can be anything;
 * default value is lowercase of the name
 */
sealed class Client(
    val source: String,
    private val identifier: String? = source.lowercase(Locale.ENGLISH)
) {
    object Android : Client(source = "Android")
    object ReactNative : Client(source = "ReactNative")
    object Expo : Client(source = "Expo")
    class Other(value: String) : Client(source = value, identifier = null)

    companion object {
        fun fromSource(source: String): Client {
            val identifier = source.lowercase(Locale.ENGLISH)
            return listOf(Android, ReactNative, Expo).find { client ->
                client.identifier == identifier
            } ?: Other(value = source)
        }
    }
}
