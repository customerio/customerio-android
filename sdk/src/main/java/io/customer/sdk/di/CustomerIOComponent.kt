package io.customer.sdk.di

import android.content.Context
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.hooks.CioHooksManager
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.lifecycle.CustomerIOActivityLifecycleCallbacks
import io.customer.sdk.module.CustomerIOAnalytics
import io.customer.sdk.repository.preference.SitePreferenceRepository
import io.customer.sdk.repository.preference.SitePreferenceRepositoryImpl
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.DateUtilImpl
import io.customer.sdk.util.DispatchersProvider
import io.customer.sdk.util.Logger

/**
 * Configuration class to configure/initialize low-level operations and objects.
 */
class CustomerIOComponent(
    private val staticComponent: CustomerIOStaticComponent,
    val context: Context,
    val analyticsModule: CustomerIOAnalytics,
    val sdkConfig: CustomerIOConfig
) : DiGraph() {

    val dispatchersProvider: DispatchersProvider
        get() = override() ?: staticComponent.dispatchersProvider

    val logger: Logger
        get() = override() ?: staticComponent.logger

    val hooksManager: HooksManager
        get() = override() ?: getSingletonInstanceCreate { CioHooksManager() }

    val dateUtil: DateUtil
        get() = override() ?: DateUtilImpl()

    val activityLifecycleCallbacks: CustomerIOActivityLifecycleCallbacks
        get() = override() ?: getSingletonInstanceCreate {
            CustomerIOActivityLifecycleCallbacks(config = sdkConfig)
        }

    val sitePreferenceRepository: SitePreferenceRepository by lazy {
        override() ?: SitePreferenceRepositoryImpl(
            context = context,
            config = sdkConfig
        )
    }
}
