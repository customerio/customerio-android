package io.customer.sdk.repository

import io.customer.base.comunication.Action
import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.base.testutils.ActionUtils
import io.customer.sdk.api.service.CustomerService
import io.customer.sdk.api.service.PushService
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.DeviceRequest
import io.customer.sdk.data.request.Metric
import io.customer.sdk.data.request.MetricEvent
import java.util.*

internal interface PushNotificationRepository {
    fun registerDeviceToken(identifier: String?, deviceToken: String): Action<Unit>
    fun deleteDeviceToken(identifier: String?, deviceToken: String?): Action<Unit>
    fun trackMetric(deliveryID: String, event: MetricEvent, deviceToken: String): Action<Unit>
}

internal class PushNotificationRepositoryImp(
    private val customerService: CustomerService,
    private val pushService: PushService
) : PushNotificationRepository {

    override fun registerDeviceToken(
        identifier: String?,
        deviceToken: String
    ): Action<Unit> {
        val device = Device(token = deviceToken, lastUsed = Date().getUnixTimestamp())
        return when {
            identifier == null -> {
                return ActionUtils.getUnidentifiedUserAction()
            }
            deviceToken.isBlank() -> {
                return ActionUtils.getErrorAction(ErrorResult(error = ErrorDetail(statusCode = StatusCode.InvalidToken)))
            }
            else -> customerService.addDevice(
                identifier = identifier,
                body = DeviceRequest(device = device)
            )
        }
    }

    override fun deleteDeviceToken(identifier: String?, deviceToken: String?): Action<Unit> {
        return when {
            // no device token, delete has already happened or is not needed
            deviceToken.isNullOrBlank() -> {
                return ActionUtils.getEmptyAction()
            }
            // no customer identified, we can safely clear the device token
            identifier.isNullOrBlank() -> {
                return ActionUtils.getEmptyAction()
            }
            else -> customerService.removeDevice(identifier = identifier, token = deviceToken)
        }
    }

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
