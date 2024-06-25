package io.customer.messaginginapp

import io.customer.messaginginapp.di.gistProvider
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.events.Metric
import io.customer.sdk.events.serializedName

class ModuleMessagingInApp(
    config: MessagingInAppModuleConfig
) : CustomerIOModule<MessagingInAppModuleConfig> {
    override val moduleName: String = MODULE_NAME
    override val moduleConfig: MessagingInAppModuleConfig = config

    private val diGraph: SDKComponent = SDKComponent
    private val eventBus = diGraph.eventBus
    private val gistProvider by lazy { diGraph.gistProvider }
    private val logger = diGraph.logger

    fun dismissMessage() {
        gistProvider.dismissMessage()
    }

    override fun initialize() {
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
            logger.debug("in-app message shown $deliveryID")
            eventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = deliveryID,
                    event = Metric.Opened.serializedName
                )
            )
        }, onAction = { deliveryID: String, _: String, action: String, name: String ->
            logger.debug("in-app message clicked $deliveryID")
            eventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = deliveryID,
                    event = Metric.Clicked.serializedName,
                    params = mapOf("action_name" to name, "action_value" to action)
                )
            )
        }, onError = { errorMessage ->
            logger.error("in-app message error occurred $errorMessage")
        })
    }

    private fun setupHooks() {
        eventBus.subscribe<Event.ScreenViewedEvent> {
            gistProvider.setCurrentRoute(it.name)
        }

        eventBus.subscribe<Event.ProfileIdentifiedEvent> {
            gistProvider.setUserToken(it.identifier)
        }

        eventBus.subscribe<Event.ResetEvent> {
            gistProvider.clearUserToken()
        }
    }

    private fun initializeGist() {
        diGraph.android().application.let {
            gistProvider.initProvider(
                application = it,
                siteId = moduleConfig.siteId,
                region = moduleConfig.region.code
            )
        }
    }

    companion object {
        const val MODULE_NAME: String = "MessagingInApp"
    }
}
