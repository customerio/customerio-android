package io.customer.sdk.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseInstrumentedTest
import io.customer.sdk.repository.preference.*
import java.util.*
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferenceRepositoryTest : BaseInstrumentedTest() {

    private lateinit var prefRepository: SharedPreferenceRepository

    @Before
    override fun setup() {
        super.setup()

        prefRepository = SharedPreferenceRepositoryImp(context)
        (prefRepository as SharedPreferenceRepositoryImp).clearAll()
    }

    @Test
    fun verifySaveSettings_givenNoConfigSavedPreviously_expectInvalidConfig() {
        val storedValues = prefRepository.loadSettings()
        storedValues.doesExist().shouldBeFalse()
    }

    @Test
    fun verifySaveSettings_givenConfigSavedPreviously_expectCorrectConfig() {
        prefRepository.saveSettings(CustomerIOStoredValues(cioConfig))

        val storedValues = prefRepository.loadSettings()
        storedValues.siteId shouldBeEqualTo cioConfig.siteId
        storedValues.apiKey shouldBeEqualTo cioConfig.apiKey
        storedValues.region shouldBeEqualTo cioConfig.region
        storedValues.client.toString() shouldBeEqualTo cioConfig.client.toString()
        storedValues.trackingApiUrl shouldBeEqualTo cioConfig.trackingApiUrl
        storedValues.autoTrackDeviceAttributes shouldBeEqualTo cioConfig.autoTrackDeviceAttributes
        storedValues.logLevel shouldBeEqualTo cioConfig.logLevel
        storedValues.backgroundQueueMinNumberOfTasks shouldBeEqualTo cioConfig.backgroundQueueMinNumberOfTasks
        storedValues.backgroundQueueSecondsDelay shouldBeEqualTo cioConfig.backgroundQueueSecondsDelay
    }
}
