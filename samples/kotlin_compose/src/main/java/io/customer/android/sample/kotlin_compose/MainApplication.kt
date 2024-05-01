package io.customer.android.sample.kotlin_compose

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.customer.android.sample.kotlin_compose.data.models.setValuesFromBuilder
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.android.sample.kotlin_compose.data.sdk.InAppMessageEventListener
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOBuilder
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class MainApplication : Application() {

    @Inject
    lateinit var preferences: PreferenceRepository

    override fun onCreate() {
        super.onCreate()
        val configuration = runBlocking {
            preferences.getConfiguration().first()
        }

        CustomerIO.Builder(
            siteId = configuration.siteId,
            apiKey = configuration.apiKey,
            appContext = this
        ).apply {
            configuration.setValuesFromBuilder(this)

            addCustomerIOModule(
                ModuleMessagingInApp(
                    config = MessagingInAppModuleConfig.Builder()
                        .setEventListener(InAppMessageEventListener()).build()
                )
            )
            addCustomerIOModule(ModuleMessagingPushFCM())

            build()
        }

        // New method to initialize CustomerIO
        // The new method should be called after the old method till the old method is removed
        // This is because the push and in-app modules are still using properties only initialized in the old method
        CustomerIOBuilder(
            applicationContext = this,
            cdpApiKey = configuration.cdpApiKey
        ).apply {
            addCustomerIOModule(
                ModuleMessagingInApp(
                    config = MessagingInAppModuleConfig.Builder()
                        .setEventListener(InAppMessageEventListener()).build()
                )
            )
            addCustomerIOModule(ModuleMessagingPushFCM())
            build()
        }
    }
}
