package io.customer.messaginginapp

import io.customer.messaginginapp.di.gistProvider
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.events.Metric

class ModuleMessagingInApp(
    config: MessagingInAppModuleConfig
) : CustomerIOModule<MessagingInAppModuleConfig>, GistListener {
    override val moduleName: String = MODULE_NAME
    override val moduleConfig: MessagingInAppModuleConfig = config

    private val eventBus = SDKComponent.eventBus
    private val gistProvider: GistProvider
        get() = SDKComponent.gistProvider
    private val logger = SDKComponent.logger

    fun dismissMessage() {
        gistProvider.dismissMessage()
    }

    override fun initialize() {
        setupHooks()
    }

    private fun setupHooks() {
        eventBus.subscribe<Event.ScreenViewedEvent> {
            gistProvider.setCurrentRoute(it.name)
        }

        eventBus.subscribe<Event.ProfileIdentifiedEvent> {
            gistProvider.setUserId(it.identifier)
        }

        eventBus.subscribe<Event.ResetEvent> {
            logger.debug("Resetting user token")
            gistProvider.reset()
        }
    }

    override fun embedMessage(message: Message, elementId: String) {
        moduleConfig.eventListener?.messageShown(InAppMessage.getFromGistMessage(message))
    }

    override fun onMessageShown(message: Message) {
        moduleConfig.eventListener?.messageShown(InAppMessage.getFromGistMessage(message))

        message.gistProperties.campaignId?.let { deliveryID ->
            logger.debug("In-app message shown with deliveryId $deliveryID")
            eventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = deliveryID,
                    event = Metric.Opened
                )
            )
        }
    }

    override fun onMessageDismissed(message: Message) {
        moduleConfig.eventListener?.messageDismissed(InAppMessage.getFromGistMessage(message))
    }

    override fun onMessageCancelled(message: Message) {}

    override fun onError(message: Message) {
        logger.error("Error occurred on message: $message")
        moduleConfig.eventListener?.errorWithMessage(InAppMessage.getFromGistMessage(message))
    }

    override fun onAction(message: Message, currentRoute: String, action: String, name: String) {
        moduleConfig.eventListener?.messageActionTaken(
            InAppMessage.getFromGistMessage(message),
            actionValue = action,
            actionName = name
        )

        message.gistProperties.campaignId?.let { deliveryID ->
            logger.debug("In-app message clicked with deliveryId: $deliveryID with action: $action and name: $name")
            if (action != "gist://close") {
                eventBus.publish(
                    Event.TrackInAppMetricEvent(
                        deliveryID = deliveryID,
                        event = Metric.Clicked,
                        params = mapOf("actionName" to name, "actionValue" to action)
                    )
                )
            }
        }
    }

    companion object {
        const val MODULE_NAME: String = "MessagingInApp"

        @JvmStatic
        fun instance(): ModuleMessagingInApp {
            return SDKComponent.modules[MODULE_NAME] as? ModuleMessagingInApp
                ?: throw IllegalStateException("ModuleMessagingInApp not initialized")
        }
    }
}
