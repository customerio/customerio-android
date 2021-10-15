package io.customer.sdk.api

import io.customer.base.comunication.Action

/**
 * Apis exposed to clients
 */
internal interface CustomerIoApi {
    fun identify(identifier: String, attributes: Map<String, Any>): Action<Unit>
    fun track(name: String, attributes: Map<String, Any>): Action<Unit>
    fun clearIdentify()
}
