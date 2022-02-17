package io.customer.sdk.repository

import io.customer.base.comunication.Action
import io.customer.base.testutils.ActionUtils
import io.customer.sdk.api.service.CustomerService
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.Event

internal interface TrackingRepository {
    fun track(
        identifier: String?,
        type: EventType,
        name: String,
        attributes: Map<String, Any>
    ): Action<Unit>
}

internal class TrackingRepositoryImp(
    private val customerService: CustomerService,
    private val attributesRepository: AttributesRepository
) : TrackingRepository {

    override fun track(
        identifier: String?,
        type: EventType,
        name: String,
        attributes: Map<String, Any>
    ): Action<Unit> {
        return if (identifier == null) {
            return ActionUtils.getUnidentifiedUserAction()
        } else customerService.track(
            identifier = identifier,
            body = Event(
                type = type,
                name = name,
                data = attributesRepository.mapToJson(attributes)
            )
        )
    }
}
