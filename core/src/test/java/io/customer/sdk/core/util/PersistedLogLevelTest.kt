package io.customer.sdk.core.util

import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.environment.BuildEnvironment
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.data.store.GlobalPreferenceStoreImpl
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersistedLogLevelTest : RobolectricTest() {

    private val store: GlobalPreferenceStore get() = GlobalPreferenceStoreImpl(contextMock)

    private fun newLogger(
        debugModeEnabled: Boolean = false,
        persistedLogLevelStore: () -> GlobalPreferenceStore? = { store }
    ) = LoggerImpl(
        buildEnvironment = object : BuildEnvironment {
            override val debugModeEnabled: Boolean = debugModeEnabled
        },
        persistedLogLevelStore = persistedLogLevelStore
    )

    @Test
    fun saveLogLevel_expectNewStoreReadsItBack() {
        store.getLogLevel().shouldBeNull()

        store.saveLogLevel(CioLogLevel.INFO)

        store.getLogLevel() shouldBeEqualTo CioLogLevel.INFO
    }

    @Test
    fun logLevel_givenPersistedLevel_expectNewLoggerUsesIt() {
        store.saveLogLevel(CioLogLevel.DEBUG)

        newLogger(debugModeEnabled = false).logLevel shouldBeEqualTo CioLogLevel.DEBUG
    }

    @Test
    fun logLevel_givenAppSetsLevel_expectAppLevelWinsImmediately() {
        store.saveLogLevel(CioLogLevel.DEBUG)
        val logger = newLogger()
        logger.logLevel shouldBeEqualTo CioLogLevel.DEBUG

        logger.logLevel = CioLogLevel.ERROR

        logger.logLevel shouldBeEqualTo CioLogLevel.ERROR
    }

    @Test
    fun logLevel_givenNothingPersisted_expectFallbackUnchanged() {
        newLogger(debugModeEnabled = false).logLevel shouldBeEqualTo CioLogLevel.ERROR
        newLogger(debugModeEnabled = true).logLevel shouldBeEqualTo CioLogLevel.DEBUG
    }

    @Test
    fun logLevel_givenStoreAvailableOnlyLater_expectPersistedLevelOnceAvailable() {
        // A logger can exist before the Android component (NotificationClickReceiverActivity creates it in a field)
        store.saveLogLevel(CioLogLevel.INFO)
        var availableStore: GlobalPreferenceStore? = null
        val logger = newLogger(debugModeEnabled = false) { availableStore }

        logger.logLevel shouldBeEqualTo CioLogLevel.ERROR

        availableStore = store

        logger.logLevel shouldBeEqualTo CioLogLevel.INFO
    }

    @Test
    fun sdkLogger_givenColdProcessBeforeSdkInit_expectPersistedLevelOnceAndroidComponentIsSetUp() {
        // A process the OS starts for a push or a tap: a fresh graph, no app-set level
        SDKComponent.reset()
        store.saveLogLevel(CioLogLevel.INFO)
        val logger = SDKComponent.logger
        logger.logLevel shouldNotBeEqualTo CioLogLevel.INFO

        // What the FCM service, the GCM receiver and NotificationClickReceiverActivity do first
        SDKComponent.setupAndroidComponent(applicationMock)

        logger.logLevel shouldBeEqualTo CioLogLevel.INFO
    }
}
