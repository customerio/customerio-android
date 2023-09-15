package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseIntegrationTest
import io.customer.sdk.di.CustomerIOSharedComponent
import io.customer.sdk.di.CustomerIOStaticComponent
import io.customer.sdk.repository.preference.CustomerIOStoredValues
import io.customer.sdk.repository.preference.SharedPreferenceRepository
import io.customer.sdk.util.LogcatLogger
import io.customer.sdk.util.StaticSettingsProvider
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class CustomerIOSharedTest : BaseIntegrationTest() {
    @Test
    fun verifyInstanceAccessedMultipleTimes_givenNoSpecialCondition_expectSameInstance() {
        val instance1 = CustomerIOShared.instance()
        val instance2 = CustomerIOShared.instance()

        instance1 shouldBeEqualTo instance2
    }

    @Test
    fun verifySharedComponentInstanceAccessedMultipleTimes_givenNoSpecialCondition_expectSameInstance() {
        val instance1 = CustomerIOShared.instance().initializeAndGetSharedComponent(context)
        val instance2 = CustomerIOShared.instance().initializeAndGetSharedComponent(context)

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

    @Test
    fun verifyAttachedWithSDK_givenNoSpecificEnvironment_expectSharedComponentToBeInitialized() {
        val diGraph = CustomerIOStaticComponent()

        val instance = CustomerIOShared.createInstance(diStaticGraph = diGraph)
        instance.attachSDKConfig(sdkConfig = cioConfig, context = context)

        val sharedGraph = instance.diSharedGraph
        sharedGraph shouldNotBe null
    }

    @Test
    fun verifyAttachedWithSDK_givenSharedComponentIsInitialized_expectConfigValuesToBeStored() {
        val diGraph = CustomerIOStaticComponent()
        val diIOSharedComponent = CustomerIOSharedComponent(context)

        val sharedPreferenceRepository: SharedPreferenceRepository = mock()
        diIOSharedComponent.overrideDependency(SharedPreferenceRepository::class.java, sharedPreferenceRepository)

        val instance = CustomerIOShared.createInstance(diStaticGraph = diGraph)
        instance.diSharedGraph = diIOSharedComponent
        instance.attachSDKConfig(sdkConfig = cioConfig, context = context)

        verify(sharedPreferenceRepository).saveSettings(CustomerIOStoredValues(cioConfig))
    }
}
