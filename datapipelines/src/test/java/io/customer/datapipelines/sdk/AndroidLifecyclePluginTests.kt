package io.customer.datapipelines.sdk

import android.app.Activity
import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.segment.analytics.kotlin.android.plugins.AndroidLifecyclePlugin
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Configuration
import com.segment.analytics.kotlin.core.ErrorHandler
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.TrackEvent
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.core.UnitTest
import io.customer.datapipelines.extensions.updateAnalyticsConfig
import io.customer.datapipelines.stubs.TestCoroutineConfiguration
import io.customer.datapipelines.utils.TestRunPlugin
import io.customer.datapipelines.utils.mockHTTPClient
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.extensions.random
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.time.Instant
import java.util.Date
import java.util.UUID
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AndroidLifecyclePluginTests {
    private val lifecyclePlugin = AndroidLifecyclePlugin()

    private lateinit var analytics: Analytics
    private val mockContext = spyk(InstrumentationRegistry.getInstrumentation().targetContext)
    private val mockApplication = mockk<Application>(relaxed = true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    init {
        val packageInfo = PackageInfo()
        packageInfo.versionCode = 100
        packageInfo.versionName = "1.0.0"

        val packageManager = mockk<PackageManager> {
            every { getPackageInfo("com.foo", 0) } returns packageInfo
        }
        every { mockApplication.packageName } returns "com.foo"
        every { mockApplication.packageManager } returns packageManager

        mockkStatic(Instant::class)
        every { Instant.now() } returns Date(0).toInstant()
        mockkStatic(UUID::class)
        every { UUID.randomUUID().toString() } returns String.random

        mockHTTPClient()
    }

    private fun createTestAnalyticsInstance(moduleConfig: DataPipelinesModuleConfig, testScope: TestScope, testDispatcher: TestDispatcher): Analytics {
        val configuration = createAnalyticsConfig(moduleConfig = moduleConfig)
        return object : Analytics(configuration, TestCoroutineConfiguration(testDispatcher, testScope)) {}
    }

    private fun createAnalyticsConfig(
        moduleConfig: DataPipelinesModuleConfig,
        errorHandler: ErrorHandler? = null
    ): Configuration = Configuration(writeKey = moduleConfig.cdpApiKey, mockApplication).let { config ->
        updateAnalyticsConfig(moduleConfig = moduleConfig, errorHandler = errorHandler).invoke(config)
        return@let config
    }

    private fun createModuleInstance(
        cdpApiKey: String = UnitTest.TEST_CDP_API_KEY,
        applyConfig: CustomerIOBuilder.() -> Unit = {}
    ): CustomerIO {
        val builder = CustomerIOBuilder(mockContext as Application, cdpApiKey)
        // Disable adding destination to analytics instance so events are not sent to the server by default
        builder.setAutoAddCustomerIODestination(false)
        // Apply custom configuration for the test
        builder.applyConfig()
        return builder.build()
    }

    @Before
    fun setup() {
        analytics = createTestAnalyticsInstance(
            createModuleInstance().moduleConfig,
            testScope,
            testDispatcher
        )
    }

    @Test
    fun `application opened is tracked`() {
        analytics.configuration.trackApplicationLifecycleEvents = true
        analytics.configuration.trackDeepLinks = false
        analytics.configuration.useLifecycleObserver = false
        analytics.add(lifecyclePlugin)
        val mockPlugin = spyk(TestRunPlugin {})
        analytics.add(mockPlugin)

        val mockActivity = mockk<Activity>()
        val mockBundle = mockk<Bundle>()

        // Simulate activity startup
        lifecyclePlugin.onActivityCreated(mockActivity, mockBundle)
        lifecyclePlugin.onActivityStarted(mockActivity)
        lifecyclePlugin.onActivityResumed(mockActivity)

        verify { mockPlugin.updateState(true) }
        val tracks = mutableListOf<TrackEvent>()
        verify { mockPlugin.track(capture(tracks)) }
        assertEquals(1, tracks.size)
        with(tracks.last()) {
            assertEquals("Application Opened", event)
            assertEquals(
                buildJsonObject {
                    put("version", "1.0.0")
                    put("build", "100")
                    put("from_background", false)
                },
                properties
            )
        }
    }

    @Test
    fun `application backgrounded is tracked`() {
        analytics.configuration.trackApplicationLifecycleEvents = true
        analytics.configuration.trackDeepLinks = false
        analytics.configuration.useLifecycleObserver = false
        analytics.add(lifecyclePlugin)
        val mockPlugin = spyk(TestRunPlugin {})
        analytics.add(mockPlugin)

        val mockActivity = mockk<Activity>()

        // Simulate activity startup
        lifecyclePlugin.onActivityPaused(mockActivity)
        lifecyclePlugin.onActivityStopped(mockActivity)
        lifecyclePlugin.onActivityDestroyed(mockActivity)

        verify { mockPlugin.updateState(true) }
        val track = slot<TrackEvent>()
        verify { mockPlugin.track(capture(track)) }
        with(track.captured) {
            assertEquals("Application Backgrounded", event)
        }
    }

    @Test
    fun `application installed is tracked`() = runTest {
        analytics.configuration.trackApplicationLifecycleEvents = true
        analytics.configuration.trackDeepLinks = false
        analytics.configuration.useLifecycleObserver = false
        analytics.add(lifecyclePlugin)

        analytics.storage.remove(Storage.Constants.AppBuild)

        val mockPlugin = spyk(TestRunPlugin {})
        analytics.add(mockPlugin)

        val mockActivity = mockk<Activity>()
        val mockBundle = mockk<Bundle>()

        // Simulate activity startup
        lifecyclePlugin.onActivityCreated(mockActivity, mockBundle)

        verify { mockPlugin.updateState(true) }
        val track = slot<TrackEvent>()
        verify { mockPlugin.track(capture(track)) }
        with(track.captured) {
            assertEquals("Application Installed", event)
            assertEquals(
                buildJsonObject {
                    put("version", "1.0.0")
                    put("build", "100")
                },
                properties
            )
        }
    }

    @Test
    fun `application updated is tracked`() = runTest {
        analytics.configuration.trackApplicationLifecycleEvents = true
        analytics.configuration.trackDeepLinks = false
        analytics.configuration.useLifecycleObserver = false
        analytics.add(lifecyclePlugin)

        analytics.storage.write(Storage.Constants.AppVersion, "0.9")
        analytics.storage.write(Storage.Constants.AppBuild, "9")

        val mockPlugin = spyk(TestRunPlugin {})
        analytics.add(mockPlugin)

        val mockActivity = mockk<Activity>()
        val mockBundle = mockk<Bundle>()

        // Simulate activity startup
        lifecyclePlugin.onActivityCreated(mockActivity, mockBundle)

        verify { mockPlugin.updateState(true) }
        val track = slot<TrackEvent>()
        verify { mockPlugin.track(capture(track)) }
        with(track.captured) {
            assertEquals("Application Updated", event)
            assertEquals(
                buildJsonObject {
                    put("version", "1.0.0")
                    put("build", "100")
                    put("previous_version", "0.9")
                    put("previous_build", "9")
                },
                properties
            )
        }
    }

    @Test
    fun `application lifecycle events not tracked when disabled`() {
        analytics.configuration.trackApplicationLifecycleEvents = false
        analytics.configuration.trackDeepLinks = false
        analytics.configuration.useLifecycleObserver = false
        analytics.add(lifecyclePlugin)
        val mockPlugin = spyk(TestRunPlugin {})
        analytics.add(mockPlugin)

        val mockActivity = mockk<Activity>()
        val mockBundle = mockk<Bundle>()

        // Simulate activity startup
        lifecyclePlugin.onActivityCreated(mockActivity, mockBundle)
        lifecyclePlugin.onActivityStarted(mockActivity)
        lifecyclePlugin.onActivityResumed(mockActivity)

        lifecyclePlugin.onActivityPaused(mockActivity)
        lifecyclePlugin.onActivityStopped(mockActivity)
        lifecyclePlugin.onActivityDestroyed(mockActivity)

        assertFalse(mockPlugin.ran)
    }

    @Test
    fun `verify all application lifecycle callbacks`() = runTest {
        // Create a spy on the lifecycle plugin to ensure lifecycle methods are called
        val spyLifecyclePlugin = spyk(lifecyclePlugin)

        // Mock analytics to use the mock application and set the lifecycle plugin
        analytics.configuration.trackApplicationLifecycleEvents = true
        analytics.configuration.trackDeepLinks = false
        analytics.configuration.useLifecycleObserver = false
        analytics.add(spyLifecyclePlugin)

        // Call setup to simulate application creation
        spyLifecyclePlugin.setup(analytics)

        // Verify that registerActivityLifecycleCallbacks was called on the mock application
        verify { mockApplication.registerActivityLifecycleCallbacks(spyLifecyclePlugin) }

        // Mock the activity and bundle
        val mockActivity = mockk<Activity>()
        val mockBundle = mockk<Bundle>()
        val mockOutBundle = mockk<Bundle>()

        // Simulate activity lifecycle
        spyLifecyclePlugin.onActivityCreated(mockActivity, mockBundle)
        spyLifecyclePlugin.onActivityStarted(mockActivity)
        spyLifecyclePlugin.onActivityResumed(mockActivity)
        spyLifecyclePlugin.onActivityPaused(mockActivity)
        spyLifecyclePlugin.onActivityStopped(mockActivity)
        spyLifecyclePlugin.onActivitySaveInstanceState(mockActivity, mockOutBundle)
        spyLifecyclePlugin.onActivityDestroyed(mockActivity)

        // Verify that the corresponding lifecycle methods of the plugin are called
        verify { spyLifecyclePlugin.onActivityCreated(mockActivity, mockBundle) }
        verify { spyLifecyclePlugin.onActivityStarted(mockActivity) }
        verify { spyLifecyclePlugin.onActivityResumed(mockActivity) }
        verify { spyLifecyclePlugin.onActivityPaused(mockActivity) }
        verify { spyLifecyclePlugin.onActivityStopped(mockActivity) }
        verify { spyLifecyclePlugin.onActivitySaveInstanceState(mockActivity, mockOutBundle) }
        verify { spyLifecyclePlugin.onActivityDestroyed(mockActivity) }
    }
}
