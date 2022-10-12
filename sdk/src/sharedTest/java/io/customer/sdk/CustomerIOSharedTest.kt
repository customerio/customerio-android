package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.di.CustomerIOStaticComponent
import io.customer.sdk.util.LogcatLogger
import io.customer.sdk.util.StaticSettingsProvider
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class CustomerIOSharedTest : BaseTest() {
    @Test
    fun verifyInstanceAccessedMultipleTimes_givenNoSpecialCondition_expectSameInstance() {
        val instance1 = CustomerIOShared.instance()
        val instance2 = CustomerIOShared.instance()

        instance1 shouldBeEqualTo instance2
    }

    @Test
    fun verifyDIGraphProvided_givenInstanceNotInitializedBefore_expectProvidedDIGraph() {
        val instance = with(CustomerIOShared) {
            clearInstance()
            createInstance(diStaticGraph = staticDIComponent)
        }

        instance.diStaticGraph shouldBeEqualTo staticDIComponent
    }

    @Test
    fun verifyAttachedWithSDK_givenNoSpecificEnvironment_expectProvidedLogLevel() {
        val diGraph = CustomerIOStaticComponent()

        val staticSettingsProvider: StaticSettingsProvider = mock()
        diGraph.overrideDependency(StaticSettingsProvider::class.java, staticSettingsProvider)
        whenever(staticSettingsProvider.isDebuggable).thenReturn(false)

        val instance = CustomerIOShared.createInstance(diStaticGraph = diGraph)
        instance.attachSDKConfig(sdkConfig = cioConfig, context = context)

        (instance.diStaticGraph.logger as LogcatLogger).logLevel shouldBeEqualTo cioConfig.logLevel
    }
}
