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
        setupMocks()
    }

    private fun setupMocks() {
        val packageInfo = PackageInfo().apply {
            versionCode = 100
            versionName = "1.0.0"
        }

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

    private fun createTestAnalyticsInstance(moduleConfig: DataPipelinesModuleConfig): Analytics {
        val configuration = createAnalyticsConfig(moduleConfig)
        return object : Analytics(configuration, TestCoroutineConfiguration(testDispatcher, testScope)) {}
    }

    private fun createAnalyticsConfig(moduleConfig: DataPipelinesModuleConfig): Configuration {
        return Configuration(writeKey = moduleConfig.cdpApiKey, mockApplication).let { config ->
            updateAnalyticsConfig(moduleConfig = moduleConfig).invoke(config)
            config
        }
    }

    private fun createModuleInstance(
        cdpApiKey: String = UnitTest.TEST_CDP_API_KEY,
        applyConfig: CustomerIOBuilder.() -> Unit = {}
    ): CustomerIO {
        val builder = CustomerIOBuilder(mockContext as Application, cdpApiKey)
        builder.setAutoAddCustomerIODestination(false)
        builder.applyConfig()
        return builder.build()
    }

    @Before
    fun setup() {
        analytics = createTestAnalyticsInstance(createModuleInstance().moduleConfig)
    }

    private fun setupAnalyticsForTest() {
        analytics.configuration.trackApplicationLifecycleEvents = true
        analytics.configuration.trackDeepLinks = false
        analytics.configuration.useLifecycleObserver = false
        analytics.add(lifecyclePlugin)
    }

    @Test
    fun track_verifyApplicationOpenedIsTracked() {
        setupAnalyticsForTest()
        val mockPlugin = spyk(TestRunPlugin {})
        analytics.add(mockPlugin)

        val mockActivity = mockk<Activity>()
        val mockBundle = mockk<Bundle>()

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
    fun track_verifyApplicationBackgroundIsTracked() {
        setupAnalyticsForTest()
        val mockPlugin = spyk(TestRunPlugin {})
        analytics.add(mockPlugin)

        val mockActivity = mockk<Activity>()

        simulateActivityPauseAndStop(mockActivity)

        verify { mockPlugin.updateState(true) }
        val track = slot<TrackEvent>()
        verify { mockPlugin.track(capture(track)) }
        with(track.captured) {
            assertEquals("Application Backgrounded", event)
        }
    }

    @Test
    fun track_verifyApplicationInstalledIsTracked() = runTest {
        setupAnalyticsForTest()
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
    fun track_verifyApplicationUpdatedIsTracked() = runTest {
        setupAnalyticsForTest()
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
    fun track_givenApplicationLifecycleDisabled_expectPluginsNotCalled() {
        analytics.configuration.trackApplicationLifecycleEvents = false
        analytics.configuration.trackDeepLinks = false
        analytics.configuration.useLifecycleObserver = false
        analytics.add(lifecyclePlugin)
        val mockPlugin = spyk(TestRunPlugin {})
        analytics.add(mockPlugin)

        val mockActivity = mockk<Activity>()
        val mockBundle = mockk<Bundle>()

        lifecyclePlugin.onActivityCreated(mockActivity, mockBundle)
        lifecyclePlugin.onActivityStarted(mockActivity)
        lifecyclePlugin.onActivityResumed(mockActivity)
        lifecyclePlugin.onActivityPaused(mockActivity)
        lifecyclePlugin.onActivityStopped(mockActivity)
        lifecyclePlugin.onActivityDestroyed(mockActivity)

        assertFalse(mockPlugin.ran)
    }

    @Test
    fun track_givenApplicationLifecycleChange_expectPluginsMethodCalled() = runTest {
        // Create a spy on the lifecycle plugin to ensure lifecycle methods are called
        val spyLifecyclePlugin = spyk(lifecyclePlugin)

        // Mock analytics to use the mock application and set the lifecycle plugin
        setupAnalyticsForTest()
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
        simulateFullActivityLifecycle(mockActivity, mockBundle, mockOutBundle, spyLifecyclePlugin)

        // Verify that the corresponding lifecycle methods of the plugin are called
        verify { spyLifecyclePlugin.onActivityCreated(mockActivity, mockBundle) }
        verify { spyLifecyclePlugin.onActivityStarted(mockActivity) }
        verify { spyLifecyclePlugin.onActivityResumed(mockActivity) }
        verify { spyLifecyclePlugin.onActivityPaused(mockActivity) }
        verify { spyLifecyclePlugin.onActivityStopped(mockActivity) }
        verify { spyLifecyclePlugin.onActivitySaveInstanceState(mockActivity, mockOutBundle) }
        verify { spyLifecyclePlugin.onActivityDestroyed(mockActivity) }
    }

    private fun simulateActivityPauseAndStop(mockActivity: Activity) {
        lifecyclePlugin.onActivityPaused(mockActivity)
        lifecyclePlugin.onActivityStopped(mockActivity)
        lifecyclePlugin.onActivityDestroyed(mockActivity)
    }

    private fun simulateFullActivityLifecycle(mockActivity: Activity, mockBundle: Bundle, mockOutBundle: Bundle, plugin: AndroidLifecyclePlugin) {
        plugin.onActivityCreated(mockActivity, mockBundle)
        plugin.onActivityStarted(mockActivity)
        plugin.onActivityResumed(mockActivity)
        plugin.onActivityPaused(mockActivity)
        plugin.onActivityStopped(mockActivity)
        plugin.onActivitySaveInstanceState(mockActivity, mockOutBundle)
        plugin.onActivityDestroyed(mockActivity)
    }
}
