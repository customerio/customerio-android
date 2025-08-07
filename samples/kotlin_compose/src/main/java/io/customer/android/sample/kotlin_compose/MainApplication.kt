package io.customer.android.sample.kotlin_compose

import android.app.Application
import io.customer.android.sample.kotlin_compose.data.models.setValuesFromBuilder
import io.customer.android.sample.kotlin_compose.data.sdk.InAppMessageEventListener
import io.customer.android.sample.kotlin_compose.di.ServiceLocator
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.data.model.Region
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainApplication : Application() {

    private val preferences by lazy { ServiceLocator.preferenceRepository }

    override fun onCreate() {
        super.onCreate()

        // Initialize our simple DI container
        ServiceLocator.initialize(this)

        val configuration = runBlocking {
            preferences.getConfiguration().first()
        }

        CustomerIOBuilder(
            applicationContext = this,
            cdpApiKey = configuration.cdpApiKey
        ).apply {
            configuration.setValuesFromBuilder(this)

            addCustomerIOModule(
                ModuleMessagingInApp(
                    config = MessagingInAppModuleConfig.Builder(
                        siteId = configuration.siteId,
                        region = Region.US
                    ).setEventListener(InAppMessageEventListener()).build()
                )
            )
            addCustomerIOModule(ModuleMessagingPushFCM())
            build()
        }
    }
}
