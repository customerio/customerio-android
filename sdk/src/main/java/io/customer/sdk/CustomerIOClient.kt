package io.customer.sdk

import io.customer.base.comunication.Action
import io.customer.base.data.Result
import io.customer.base.data.Success
import io.customer.sdk.api.CustomerIoApi
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.repository.IdentityRepository
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.repository.PushNotificationRepository
import io.customer.sdk.repository.TrackingRepository

/**
 * CustomerIoClient is client class to hold all repositories and act as a bridge between
 * repositories and `CustomerIo` class
 */
internal class CustomerIOClient(
    private val identityRepository: IdentityRepository,
    private val preferenceRepository: PreferenceRepository,
    private val trackingRepository: TrackingRepository,
    private val pushNotificationRepository: PushNotificationRepository
) : CustomerIoApi {

    override fun identify(identifier: String, attributes: Map<String, Any>): Action<Unit> {
        return object : Action<Unit> {
            val action by lazy { identityRepository.identify(identifier, attributes) }
            override fun execute(): Result<Unit> {
                val result = action.execute()
                if (result is Success) {
                    preferenceRepository.saveIdentifier(identifier = identifier)
                }
                return result
            }

            override fun enqueue(callback: Action.Callback<Unit>) {
                action.enqueue {
                    if (it is Success) {
                        preferenceRepository.saveIdentifier(identifier = identifier)
                    }
                    callback.onResult(it)
                }
            }

            override fun cancel() {
                action.cancel()
            }
        }
    }

    override fun track(name: String, attributes: Map<String, Any>): Action<Unit> {
        val identifier = preferenceRepository.getIdentifier()
        return trackingRepository.track(identifier, name, attributes)
    }

    override fun clearIdentify() {
        val identifier = preferenceRepository.getIdentifier()
        identifier?.let {
            preferenceRepository.removeIdentifier(it)
        }
    }

    override fun registerDeviceToken(deviceToken: String): Action<Unit> {
        val identifier = preferenceRepository.getIdentifier()
        return object : Action<Unit> {
            val action by lazy {
                pushNotificationRepository.registerDeviceToken(
                    identifier,
                    deviceToken
                )
            }

            override fun execute(): Result<Unit> {
                val result = action.execute()
                if (result is Success) {
                    preferenceRepository.saveDeviceToken(token = deviceToken)
                }
                return result
            }

            override fun enqueue(callback: Action.Callback<Unit>) {
                action.enqueue {
                    if (it is Success) {
                        preferenceRepository.saveDeviceToken(token = deviceToken)
                    }
                    callback.onResult(it)
                }
            }

            override fun cancel() {
                action.cancel()
            }
        }
    }

    override fun deleteDeviceToken(): Action<Unit> {
        val identifier = preferenceRepository.getIdentifier()
        val deviceToken = preferenceRepository.getDeviceToken()
        return object : Action<Unit> {
            val action by lazy {
                pushNotificationRepository.deleteDeviceToken(
                    identifier,
                    deviceToken
                )
            }

            override fun execute(): Result<Unit> {
                val result = action.execute()
                if (result is Success && deviceToken != null) {
                    preferenceRepository.removeDeviceToken(token = deviceToken)
                }
                return result
            }

            override fun enqueue(callback: Action.Callback<Unit>) {
                action.enqueue {
                    if (it is Success && deviceToken != null) {
                        preferenceRepository.removeDeviceToken(token = deviceToken)
                    }
                    callback.onResult(it)
                }
            }

            override fun cancel() {
                action.cancel()
            }
        }
    }

    override fun trackMetric(deliveryID: String, event: MetricEvent, deviceToken: String) {
        pushNotificationRepository
    }
}
