package io.customer.sdk.api

/**
 * Apis exposed to clients
 */
interface CustomerIOApi {
    fun identify(identifier: String, attributes: Map<String, Any>)
    fun track(name: String, attributes: Map<String, Any>)
    fun clearIdentify()
    fun screen(name: String, attributes: Map<String, Any>)
}
