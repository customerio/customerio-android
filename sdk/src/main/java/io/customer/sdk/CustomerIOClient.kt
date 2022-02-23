package io.customer.sdk

import io.customer.base.comunication.Action
import io.customer.base.data.Result
import io.customer.base.data.Success
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.repository.IdentityRepository
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.repository.PushNotificationRepository
import io.customer.sdk.util.Logger
import java.util.*

/**
 * CustomerIoClient is client class to hold all repositories and act as a bridge between
 * repositories and `CustomerIo` class
 */
internal class CustomerIOClient(
    private val identityRepository: IdentityRepository,
    private val preferenceRepository: PreferenceRepository,
    private val pushNotificationRepository: PushNotificationRepository,
    private val backgroundQueue: Queue,
    private val logger: Logger
) : CustomerIOApi {

    override fun identify(identifier: String, attributes: Map<String, Any>): Action<Unit> {
        return object : Action<Unit> {
            val action by lazy { identityRepository.identify(identifier, attributes) }
            override fun execute(): Result<Unit> {
                val result = action.execute()
                if (result is Success) {
                    logger.info("logged in $identifier successfully. Saving identifier to storage")
                    preferenceRepository.saveIdentifier(identifier = identifier)
                }
                return result
            }

            override fun enqueue(callback: Action.Callback<Unit>) {
                action.enqueue {
                    if (it is Success) {
                        logger.info("logged in $identifier successfully. Saving identifier to storage")
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

    override fun track(name: String, attributes: Map<String, Any>) {
        return track(EventType.event, name, attributes)
    }

    fun track(eventType: EventType, name: String, attributes: Map<String, Any>) {
        val eventTypeDescription = if (eventType == EventType.screen) "track screen view event" else "track event"

        logger.info("$eventTypeDescription $name")
        logger.debug("$eventTypeDescription $name attributes: $attributes")

        val identifier = preferenceRepository.getIdentifier()
        if (identifier == null) {
            // when we have anonymous profiles implemented in the SDK, we can decide to not
            // ignore events when a profile is not logged in yet.
            logger.info("ignoring $eventTypeDescription $name because no profile currently identified")
            return
        }

        backgroundQueue.addTask(QueueTaskType.TrackEvent.name, TrackEventQueueTaskData(name, Event(name, eventType, attributes, Date().getUnixTimestamp())))
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

    override fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String
    ) = pushNotificationRepository.trackMetric(deliveryID, event, deviceToken)

    override fun screen(name: String, attributes: Map<String, Any>) {
        return track(EventType.screen, name, attributes)
    }
}
