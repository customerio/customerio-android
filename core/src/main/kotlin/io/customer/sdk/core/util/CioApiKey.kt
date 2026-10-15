package io.customer.sdk.core.util

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.data.model.Region

/**
 * Reads the prefix of a Customer.io API key: `{sk|pk}_{us|eu}_...`.
 * `sk_` keys are secret and must stay on servers, `pk_` keys are public and safe to ship in apps.
 * The region is read from any key shaped `xx_{us|eu}_`, so only the kind checks depend on the prefix names.
 * Legacy keys have no prefix. The server validates the rest of the key.
 */
@InternalCustomerIOApi
object CioApiKey {
    private val prefixRegex = Regex("^([a-z]{2})_(us|eu)_")

    const val SECRET_KEY_ERROR = "Secret (sk_) keys must not be used in apps. Use your public key in apps."

    /**
     * Any `sk_` key is secret, even with an unknown region, so it is never sent from an app.
     */
    fun isSecretKey(key: String): Boolean = key.startsWith("sk_")

    fun isPublicKey(key: String): Boolean = kind(key) == "pk"

    /**
     * Region encoded in the key prefix, or null for legacy keys.
     */
    fun region(key: String): Region? {
        val code = prefixRegex.find(key)?.groupValues?.get(2) ?: return null
        return Region.getRegion(code)
    }

    private fun kind(key: String): String? = prefixRegex.find(key)?.groupValues?.get(1)
}
