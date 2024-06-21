package io.customer.messaginginapp

import android.app.Application
import androidx.annotation.VisibleForTesting
import io.customer.messaginginapp.di.gistProvider
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.di.CustomerIOComponent

class ModuleMessagingInApp
@VisibleForTesting
internal constructor(
    override val moduleConfig: MessagingInAppModuleConfig = MessagingInAppModuleConfig.default(),
    private val overrideDiGraph: CustomerIOComponent?
) : CustomerIOModule<MessagingInAppModuleConfig> {
    @JvmOverloads
    @Deprecated(
        "organizationId no longer required and will be removed in future",
        replaceWith = ReplaceWith("constructor(config: MessagingInAppModuleConfig)")
    )
    constructor(
        organizationId: String,
        config: MessagingInAppModuleConfig = MessagingInAppModuleConfig.default()
    ) : this(
        moduleConfig = config,
        overrideDiGraph = null
    )

    @JvmOverloads
    constructor(
        config: MessagingInAppModuleConfig = MessagingInAppModuleConfig.default()
    ) : this(
        moduleConfig = config,
        overrideDiGraph = null
    )

    override val moduleName: String = ModuleMessagingInApp.moduleName

    private val diGraph: SDKComponent = SDKComponent

    private val eventBus = diGraph.eventBus

    private val gistProvider by lazy { diGraph.gistProvider }

    private val logger = diGraph.logger

    private val oldDiGraph: CustomerIOComponent
        get() = overrideDiGraph ?: CustomerIO.instance().diGraph

    private val config: CustomerIOConfig
        get() = oldDiGraph.sdkConfig

    companion object {
        const val moduleName: String = "MessagingInApp"
    }

    fun dismissMessage() {
        gistProvider.dismissMessage()
    }

    override fun initialize() {
        initializeGist(config)
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
                    event = MetricEvent.opened.name
                )
            )
        }, onAction = { deliveryID: String, _: String, action: String, name: String ->
            logger.debug("in-app message clicked $deliveryID")
            eventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = deliveryID,
                    event = MetricEvent.clicked.name,
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

    // TODO: Remove config and replace it with moduleConfig
    private fun initializeGist(config: CustomerIOConfig) {
        // TODO: This should not be nullable
        (diGraph.androidSDKComponent?.applicationContext as? Application)?.let {
            gistProvider.initProvider(
                application = it,
                siteId = config.siteId,
                region = config.region.code
            )
        }
    }
}
