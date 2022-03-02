package io.customer.sdk.repository

import io.customer.base.comunication.Action
import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.base.utils.ActionUtils
import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.api.service.PushService
import io.customer.sdk.data.request.Metric
import io.customer.sdk.data.request.MetricEvent
import java.util.*

internal interface PushNotificationRepository {
    fun trackMetric(deliveryID: String, event: MetricEvent, deviceToken: String): Action<Unit>
}

internal class PushNotificationRepositoryImp(
    private val customerIOService: CustomerIOService,
    private val pushService: PushService
) : PushNotificationRepository {

    override fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String
    ): Action<Unit> {

        if (deliveryID.isBlank() || deviceToken.isBlank()) {
            return ActionUtils.getErrorAction(
                ErrorResult(
                    error = ErrorDetail(
                        statusCode = StatusCode.BadRequest,
                        message = "Delivery ID and Token can't be empty"
                    )
                )
            )
        }

        val metric = Metric(
            deliveryID = deliveryID,
            event = event,
            deviceToken = deviceToken,
            timestamp = Date().getUnixTimestamp()
        )
        return pushService.trackMetric(metric)
    }
}
