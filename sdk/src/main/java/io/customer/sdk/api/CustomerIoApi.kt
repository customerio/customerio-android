package io.customer.sdk.api

import io.customer.base.comunication.Action
import io.customer.sdk.data.model.IdentityAttributeMap

/**
 * List of Apis exposed to clients
 */
internal interface CustomerIoApi {
    fun identify(identifier: String, attributes: IdentityAttributeMap): Action<Unit>
    fun track(name: String, attributes: Map<String, Any>): Action<Unit>
}
