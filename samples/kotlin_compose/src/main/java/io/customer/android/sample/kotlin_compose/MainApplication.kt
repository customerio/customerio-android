package io.customer.android.sample.kotlin_compose

import android.app.Application
import android.os.Handler
import android.os.Looper
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

        // TODO: Remove old builder and use new builder only to initialize the SDK
        // The new method should be called after the old method till the old method is removed
        // This is because the push and in-app modules are still using properties only initialized in the old method
        io.customer.sdk.android.CustomerIO.Builder(
            siteId = configuration.siteId,
            apiKey = BuildConfig.API_KEY,
            appContext = this
        ).build()
        // TODO: Remove this once push module is decoupled and started using EventBus
        Handler(Looper.getMainLooper()).postDelayed({
            val token = io.customer.sdk.android.CustomerIO.instance().registeredDeviceToken
            if (token != null) {
                CustomerIO.instance().registerDeviceToken(token)
            }
        }, 3000)

        CustomerIOBuilder(
            applicationContext = this,
            cdpApiKey = configuration.cdpApiKey
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
    }
}
