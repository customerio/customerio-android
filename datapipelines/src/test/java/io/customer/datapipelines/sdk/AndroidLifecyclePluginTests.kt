package io.customer.datapipelines.sdk

import android.app.Activity
import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.segment.analytics.kotlin.android.plugins.AndroidLifecyclePlugin
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.TrackEvent
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.support.core.UnitTest
import io.customer.datapipelines.support.utils.TestRunPlugin
import io.customer.datapipelines.support.utils.createTestAnalyticsInstance
import io.customer.datapipelines.support.utils.mockHTTPClient
import io.customer.sdk.CustomerIOBuilder
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

    private fun createModuleConfig(
        cdpApiKey: String = UnitTest.TEST_CDP_API_KEY
    ): DataPipelinesModuleConfig {
        val builder = CustomerIOBuilder(mockContext as Application, cdpApiKey)
        builder.setAutoAddCustomerIODestination(false)
        return builder.build().moduleConfig
    }

    @Before
    fun setup() {
        analytics = createTestAnalyticsInstance(createModuleConfig(), mockApplication)
    }

    @Test
    fun track_verifyApplicationOpenedIsTracked() {
        analytics.add(lifecyclePlugin)

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
        analytics.add(lifecyclePlugin)

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
    fun track_verifyApplicationUpdatedIsTracked() = runTest {
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
    fun track_givenApplicationLifecycleDisabled_expectPluginsNotCalled() {
        analytics.configuration.trackApplicationLifecycleEvents = false

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
        analytics.add(lifecyclePlugin)
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
