package io.customer.messaginginapp

import android.app.Application
import io.customer.messaginginapp.di.gistProvider
import io.customer.messaginginapp.hook.ModuleInAppHookProvider
import io.customer.sdk.CustomerIO
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.module.CustomerIOModule
import io.customer.sdk.repository.TrackRepository

class ModuleMessagingInApp internal constructor(
    override val moduleConfig: MessagingInAppModuleConfig = MessagingInAppModuleConfig.default(),
    private val overrideDiGraph: CustomerIOComponent?,
    private val organizationId: String
) : CustomerIOModule<MessagingInAppModuleConfig> {

    @JvmOverloads
    constructor(
        organizationId: String,
        config: MessagingInAppModuleConfig = MessagingInAppModuleConfig.default()
    ) : this(
        moduleConfig = config,
        overrideDiGraph = null,
        organizationId = organizationId
    )

    override val moduleName: String
        get() = "MessagingInApp"

    private val diGraph: CustomerIOComponent
        get() = overrideDiGraph ?: CustomerIO.instance().diGraph

    private val trackRepository: TrackRepository
        get() = diGraph.trackRepository

    private val identifier: String?
        get() = diGraph.sitePreferenceRepository.getIdentifier()

    private val hooksManager: HooksManager by lazy { diGraph.hooksManager }

    private val gistProvider by lazy { diGraph.gistProvider }

    private val logger by lazy { diGraph.logger }

    override fun initialize() {
        initializeGist(organizationId)
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
        gistProvider.subscribeToEvents(
            onMessageShown = { deliveryID ->
                logger.debug("in-app message shown $deliveryID")
                trackRepository.trackInAppMetric(
                    deliveryID = deliveryID,
                    event = MetricEvent.opened
                )
            },
            onAction = { deliveryID: String, _: String, _: String, _: String ->
                logger.debug("in-app message clicked $deliveryID")
                trackRepository.trackInAppMetric(
                    deliveryID = deliveryID,
                    event = MetricEvent.clicked
                )
            },
            onError = { errorMessage ->
                logger.error("in-app message error occurred $errorMessage")
            }
        )
    }

    private fun setupHooks() {
        hooksManager.add(
            module = HookModule.MessagingInApp,
            subscriber = ModuleInAppHookProvider()
        )
    }

    private fun initializeGist(organizationId: String) {
        gistProvider.initProvider(
            application = diGraph.context.applicationContext as Application,
            organizationId = organizationId
        )

        // if identifier is already present, set the userToken again
        identifier?.let { gistProvider.setUserToken(it) }
    }
}
