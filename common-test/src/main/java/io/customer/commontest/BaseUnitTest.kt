package io.customer.commontest

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.extensions.random
import io.customer.sdk.repository.preference.SharedPreferenceRepository
import io.customer.sdk.util.Logger
import org.mockito.kotlin.mock

abstract class BaseUnitTest : BaseTest() {

    override val context: Context = mock()
    override val application: Application = mock()

    override fun setup(cioConfig: CustomerIOConfig) {
        super.setup(cioConfig)

        CustomerIO.Builder(
            siteId = siteId,
            apiKey = String.random,
            region = Region.US,
            appContext = application
        ).apply {
            overrideDiGraph = di
        }.build()
    }

    @SuppressLint("VisibleForTests")
    override fun overrideDependencies() {
        super.overrideDependencies()
        staticDIComponent.overrideDependency(Logger::class.java, mock())
        sharedDIComponent.overrideDependency(SharedPreferenceRepository::class.java, mock())
    }
}
