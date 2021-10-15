package io.customer.sdk.repository

import io.customer.base.comunication.Action
import io.customer.base.data.ErrorResult
import io.customer.base.data.Result
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.sdk.api.service.CustomerService
import io.customer.sdk.data.request.Event

internal interface TrackingRepository {
    fun track(identifier: String?, name: String, attributes: Map<String, Any>): Action<Unit>
}

internal class TrackingRepositoryImp(
    private val customerService: CustomerService,
    private val attributesRepository: AttributesRepository
) : TrackingRepository {

    override fun track(
        identifier: String?,
        name: String,
        attributes: Map<String, Any>
    ): Action<Unit> {
        return if (identifier == null) {
            identifier ?: return object : Action<Unit> {
                override fun execute(): Result<Unit> {
                    return ErrorResult(
                        error = ErrorDetail(
                            statusCode = StatusCode.UnIdentifiedUser
                        )
                    )
                }

                override fun enqueue(callback: Action.Callback<Unit>) {
                    callback.onResult(
                        ErrorResult(
                            error = ErrorDetail(
                                statusCode = StatusCode.UnIdentifiedUser
                            )
                        )
                    )
                }

                override fun cancel() {}
            }
        } else customerService.track(
            identifier = identifier,
            body = Event(name = name, data = attributesRepository.mapToJson(attributes))
        )
    }
}
