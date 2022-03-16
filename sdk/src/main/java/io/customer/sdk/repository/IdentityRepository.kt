package io.customer.sdk.repository

import io.customer.base.comunication.Action
import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.model.verify

internal interface IdentityRepository {
    fun identify(identifier: String, attributes: CustomAttributes): Action<Unit>
}

internal class IdentityRepositoryImpl(
    private val customerIOService: CustomerIOService
) : IdentityRepository {

    override fun identify(identifier: String, attributes: CustomAttributes): Action<Unit> {
        return customerIOService.identifyCustomer(
            identifier = identifier,
            body = attributes.verify()
        )
    }
}
