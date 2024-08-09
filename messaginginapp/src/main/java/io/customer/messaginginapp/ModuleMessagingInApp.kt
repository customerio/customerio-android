package io.customer.messaginginapp

import io.customer.messaginginapp.di.gistProvider
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.domain.InAppMessagingAction
import io.customer.messaginginapp.domain.InAppMessagingManager
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.events.Metric

class ModuleMessagingInApp(
    config: MessagingInAppModuleConfig
) : CustomerIOModule<MessagingInAppModuleConfig> {
    override val moduleName: String = MODULE_NAME
    override val moduleConfig: MessagingInAppModuleConfig = config

    private val diGraph: SDKComponent = SDKComponent
    private val eventBus = diGraph.eventBus
    private val gistProvider by lazy { diGraph.gistProvider }

    private val inAppMessagingManager: InAppMessagingManager = SDKComponent.inAppMessagingManager

    fun dismissMessage() {
        gistProvider.dismissMessage()
    }

    override fun initialize() {
        inAppMessagingManager.dispatch(InAppMessagingAction.Initialize)
        initializeGist()
        setupHooks()
        configureSdkModule(moduleConfig)
        setupGistCallbacks()
    }

    private fun configureSdkModule(moduleConfig: MessagingInAppModuleConfig) {
        moduleConfig.eventListener?.let { eventListener ->
            gistProvider.setListener(eventListener)
        }
    }

    private fun setupGistCallbacks() {
        gistProvider.subscribeToEvents(onMessageShown = { deliveryID ->
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("in-app message shown in callback $deliveryID"))
            eventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = deliveryID,
                    event = Metric.Opened
                )
            )
        }, onAction = { deliveryID: String, _: String, action: String, name: String ->
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("in-app message clicked in callback $deliveryID"))
            eventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = deliveryID,
                    event = Metric.Clicked,
                    params = mapOf("action_name" to name, "action_value" to action)
                )
            )
        }, onError = { errorMessage ->
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("in-app message error occurred $errorMessage"))
        })
    }

    private fun setupHooks() {
        eventBus.subscribe<Event.ScreenViewedEvent> {
            inAppMessagingManager.dispatch(InAppMessagingAction.SetCurrentRoute(it.name))
            gistProvider.setCurrentRoute(it.name)
        }

        eventBus.subscribe<Event.ProfileIdentifiedEvent> {
            inAppMessagingManager.dispatch(InAppMessagingAction.SetUser(it.identifier))
            gistProvider.setUserToken(it.identifier)
        }

        eventBus.subscribe<Event.ResetEvent> {
            inAppMessagingManager.dispatch(InAppMessagingAction.ClearUser)
            gistProvider.clearUserToken()
        }
    }

    private fun initializeGist() {
        gistProvider.initProvider(
            application = diGraph.android().application,
            siteId = moduleConfig.siteId,
            region = moduleConfig.region.code
        )
    }

    companion object {
        const val MODULE_NAME: String = "MessagingInApp"
    }
}
