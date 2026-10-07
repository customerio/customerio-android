package io.customer.sdk.core.util

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.data.model.Region

/**
 * Reads the prefix of a Customer.io API key: `{ak|wk}_{us|eu}_...`.
 * `ak_` keys are secret and must stay on servers, `wk_` keys are public and safe to ship in apps.
 * Legacy keys have no prefix. The server validates the rest of the key.
 */
@InternalCustomerIOApi
object CioApiKey {
    private val prefixRegex = Regex("^(ak|wk)_(us|eu)_")

    const val SECRET_KEY_ERROR = "Secret (ak_) keys must not be used in apps. Use your public key in apps."

    /**
     * Any `ak_` key is secret, even with an unknown region, so it is never sent from an app.
     */
    fun isSecretKey(key: String): Boolean = key.startsWith("ak_")

    fun isPublicKey(key: String): Boolean = kind(key) == "wk"

    /**
     * Region encoded in the key prefix, or null for legacy keys.
     */
    fun region(key: String): Region? {
        val code = prefixRegex.find(key)?.groupValues?.get(2) ?: return null
        return Region.getRegion(code)
    }

    private fun kind(key: String): String? = prefixRegex.find(key)?.groupValues?.get(1)
}
