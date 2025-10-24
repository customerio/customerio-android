package io.customer.datapipelines.sdk

import android.app.Activity
import android.os.Bundle
import com.segment.analytics.kotlin.android.plugins.AndroidLifecyclePlugin
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.TrackEvent
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.DataPipelinesTestConfig
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.utils.TestRunPlugin
import io.customer.sdk.util.EventNames
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidLifecyclePluginTests : IntegrationTest() {
    private val lifecyclePlugin = AndroidLifecyclePlugin()

    init {
        mockkStatic(Instant::class)
        every { Instant.now() } returns Date(0).toInstant()
        mockkStatic(UUID::class)
        every { UUID.randomUUID().toString() } returns String.random
    }

    override fun setup(testConfig: TestConfig) {
        // Keep setup empty to avoid calling super.setup() as it will initialize the SDK
        // and we want to test the SDK with different configurations in each test
    }

    private fun setupWithConfig(testConfig: DataPipelinesTestConfig) {
        super.setup(testConfig)
    }

    private fun setupTestEnvironmentWithLifecyclePlugin() {
        setupWithConfig(
            testConfiguration {
                sdkConfig { trackApplicationLifecycleEvents(true) }
                analytics { add(lifecyclePlugin) }
            }
        )
    }

    @Test
    fun track_verifyApplicationOpenedIsTracked() {
        setupTestEnvironmentWithLifecyclePlugin()

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
        // 1. Application Installed
        // 2. Application Opened
        assertEquals(2, tracks.size)
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
        setupTestEnvironmentWithLifecyclePlugin()

        val mockPlugin = spyk(TestRunPlugin {})
        analytics.add(mockPlugin)

        val mockActivity = mockk<Activity>()

        simulateActivityPauseAndStop(mockActivity)

        verify { mockPlugin.updateState(true) }
        val track = slot<TrackEvent>()
        verify { mockPlugin.track(capture(track)) }
        with(track.captured) {
            assertEquals(EventNames.APPLICATION_BACKGROUNDED, event)
        }
    }

    @Test
    fun track_verifyApplicationInstalledIsTracked() = runTest {
        setupTestEnvironmentWithLifecyclePlugin()

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
        setupTestEnvironmentWithLifecyclePlugin()

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
        setupWithConfig(
            testConfiguration {
                sdkConfig { trackApplicationLifecycleEvents(false) }
                analytics { add(lifecyclePlugin) }
            }
        )

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
        setupWithConfig(
            testConfiguration {
                sdkConfig { trackApplicationLifecycleEvents(true) }
                analytics { add(spyLifecyclePlugin) }
            }
        )

        // Verify that registerActivityLifecycleCallbacks was called on the mock application
        verify { applicationMock.registerActivityLifecycleCallbacks(spyLifecyclePlugin) }

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
