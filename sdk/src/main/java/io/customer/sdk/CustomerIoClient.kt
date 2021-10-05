package io.customer.sdk

import io.customer.base.comunication.Action
import io.customer.sdk.api.CustomerIoApi
import io.customer.sdk.data.model.IdentityAttributeMap
import io.customer.sdk.repository.IdentityRepository

/**
 * CustomerIoClient is client class to hold all repositories and act as a bridge between
 * repositories and `CustomerIo` class
 */
internal class CustomerIoClient(
    private val identityRepository: IdentityRepository
) : CustomerIoApi {

    override fun identify(identifier: String, attributes: IdentityAttributeMap): Action<Unit> {
        return identityRepository.identify(identifier, attributes)
    }
}
