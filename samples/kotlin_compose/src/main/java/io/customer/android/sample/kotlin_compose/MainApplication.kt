package io.customer.android.sample.kotlin_compose

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.customer.android.sample.kotlin_compose.data.models.setValuesFromBuilder
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.android.sample.kotlin_compose.data.sdk.InAppMessageEventListener
import io.customer.datapipeline.DataPipelineModuleConfig
import io.customer.datapipeline.ModuleDataPipeline
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.sdk.CustomerIO
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
            addCustomerIOModule(
                ModuleDataPipeline(
                    DataPipelineModuleConfig.Builder("API-KEY").build()
                )
            )

            build()
        }
    }
}
