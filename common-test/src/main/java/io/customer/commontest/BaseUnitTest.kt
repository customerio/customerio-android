package io.customer.commontest

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.repository.preference.SharedPreferenceRepository
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.DispatchersProvider
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
        // Override any dependencies required for the tests
        overrideDependencies()
    }

    @SuppressLint("VisibleForTests")
    fun overrideDependencies() {
        staticDIComponent.overrideDependency(Logger::class.java, mock())
        sharedDIComponent.overrideDependency(SharedPreferenceRepository::class.java, mock())

        di.overrideDependency(DateUtil::class.java, dateUtilStub)
        di.overrideDependency(DeviceStore::class.java, deviceStore)
        di.overrideDependency(DispatchersProvider::class.java, dispatchersProviderStub)
    }
}
