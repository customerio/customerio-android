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
import org.mockito.kotlin.whenever

/**
 * Base class for a unit test class to subclass. If you want to create integration tests, use [BaseIntegrationTest].
 * Meant to provide convenience to test classes with properties and functions tests may use.
 */
abstract class BaseUnitTest : BaseTest() {

    override val application: Application = mock<Application>().apply {
        whenever(applicationContext).thenReturn(this)
    }
    override val context: Context = application

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
