package io.customer.messaginginapp

import android.app.Application
import io.customer.messaginginapp.di.gistProvider
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOModule
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.hooks.ModuleHook
import io.customer.sdk.hooks.ModuleHookProvider
import io.customer.sdk.repository.TrackRepository

class ModuleMessagingInApp internal constructor(
    private val overrideDiGraph: CustomerIOComponent?,
    private val organizationId: String
) : CustomerIOModule {

    constructor(organizationId: String) : this(
        overrideDiGraph = null,
        organizationId = organizationId
    )

    override val moduleName: String
        get() = "MessagingInApp"

    private val diGraph: CustomerIOComponent
        get() = overrideDiGraph ?: CustomerIO.instance().diGraph

    private val trackRepository: TrackRepository
        get() = diGraph.trackRepository

    private val hooksManager: HooksManager by lazy { diGraph.hooksManager }

    private val gistProvider by lazy { diGraph.gistProvider }

    override fun initialize() {
        initializeGist(organizationId)
        setupHooks()
        setupGistCallbacks()
    }

    private fun setupGistCallbacks() {
        gistProvider.subscribeToEvents { deliveryID ->
            trackRepository.trackInAppMetric(
                deliveryID = deliveryID,
                event = MetricEvent.opened
            )
        }
    }

    private fun setupHooks() {
        hooksManager.add(object : ModuleHookProvider() {
            override fun profileIdentifiedHook(hook: ModuleHook.ProfileIdentifiedHook) {
                gistProvider.setUserToken(hook.identifier)
            }

            override fun screenTrackedHook(hook: ModuleHook.ScreenTrackedHook) {
                gistProvider.setCurrentRoute(hook.screen)
            }

            override fun beforeProfileStoppedBeingIdentified(hook: ModuleHook.BeforeProfileStoppedBeingIdentified) {
                gistProvider.clearUserToken()
            }
        })
    }

    private fun initializeGist(organizationId: String) {
        gistProvider.initProvider(
            application = diGraph.context as Application,
            organizationId = organizationId
        )
    }
}
