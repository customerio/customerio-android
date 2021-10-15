package io.customer.sdk.repository

import io.customer.base.comunication.Action
import io.customer.sdk.api.service.CustomerService

internal interface IdentityRepository {
    fun identify(identifier: String, attributes: Map<String, Any>): Action<Unit>
}

internal class IdentityRepositoryImpl(
    private val customerService: CustomerService,
    private val attributesRepository: AttributesRepository
) : IdentityRepository {

    override fun identify(identifier: String, attributes: Map<String, Any>): Action<Unit> {
        return customerService.identifyCustomer(
            identifier = identifier,
            body = attributesRepository.mapToJson(attributes)
        )
    }
}
