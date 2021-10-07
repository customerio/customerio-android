package io.customer.sdk.repository

import io.customer.base.comunication.Action
import io.customer.sdk.api.service.CustomerService
import io.customer.sdk.data.model.IdentityAttributeMap

internal interface IdentityRepository {
    fun identify(identifier: String, attributes: IdentityAttributeMap): Action<Unit>
}

internal class IdentityRepositoryImpl(
    private val customerService: CustomerService
) : IdentityRepository {

    override fun identify(identifier: String, attributes: IdentityAttributeMap): Action<Unit> {
        return customerService.identifyCustomer(
            identifier = identifier,
            body = attributes.mapValues { it.value.getValue() }
        )
    }
}
