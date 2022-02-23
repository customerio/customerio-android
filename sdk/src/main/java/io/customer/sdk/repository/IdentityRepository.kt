package io.customer.sdk.repository

import io.customer.base.comunication.Action
import io.customer.sdk.api.service.CustomerIOService

internal interface IdentityRepository {
    fun identify(identifier: String, attributes: Map<String, Any>): Action<Unit>
}

internal class IdentityRepositoryImpl(
    private val customerIOService: CustomerIOService,
    private val attributesRepository: AttributesRepository
) : IdentityRepository {

    override fun identify(identifier: String, attributes: Map<String, Any>): Action<Unit> {
        return customerIOService.identifyCustomer(
            identifier = identifier,
            body = attributesRepository.mapToJson(attributes)
        )
    }
}
