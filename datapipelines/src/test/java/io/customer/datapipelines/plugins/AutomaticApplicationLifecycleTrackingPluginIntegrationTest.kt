package io.customer.datapipelines.plugins

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers the version lookup and foreground check that [AutomaticApplicationLifecycleTrackingPluginTest] replaces
 * with fakes. Robolectric doesn't start the process lifecycle, so the foreground check falls back to the process
 * importance reported by ActivityManager, which each test sets.
 */
@RunWith(RobolectricTestRunner::class)
class AutomaticApplicationLifecycleTrackingPluginIntegrationTest : IntegrationTest() {

    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        // The SDK's own instance stays off, so only the instance under test tracks lifecycle events
        super.setup(testConfiguration { sdkConfig { trackApplicationLifecycleEvents(false) } })

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
        runTest {
            analytics.storage.remove(Storage.Constants.AppVersion)
            analytics.storage.remove(Storage.Constants.AppBuild)
            analytics.storage.remove(Storage.Constants.LegacyAppBuild)
        }
    }

    private val trackedEvents: List<Pair<String, JsonObject>>
        get() = outputReaderPlugin.trackEvents.map { it.event to it.properties }

    private fun givenProcessImportance(importance: Int) {
        val activityManager = ApplicationProvider.getApplicationContext<Application>().getSystemService(ActivityManager::class.java)
        shadowOf(activityManager).setProcesses(
            listOf(
                RunningAppProcessInfo().apply {
                    pid = Process.myPid()
                    this.importance = importance
                }
            )
        )
    }

    private fun setupPlugin() {
        analytics.add(AutomaticApplicationLifecycleTrackingPlugin(applicationMock))
    }

    // Package info that RobolectricTest provides for the test app
    private val installed = "Application Installed" to buildJsonObject {
        put("version", "1.0.0")
        put("build", "100")
    }
    private val firstOpened = "Application Opened" to buildJsonObject {
        put("version", "1.0.0")
        put("build", "100")
        put("from_background", false)
    }
    private val foregrounded = "Application Foregrounded" to emptyJsonObject

    @Test
    fun setup_givenProcessInForeground_expectInstalledWithPackageVersionAndFirstOpen() {
        givenProcessImportance(RunningAppProcessInfo.IMPORTANCE_FOREGROUND)

        setupPlugin()

        trackedEvents shouldBeEqualTo listOf(installed, firstOpened, foregrounded)
    }

    @Test
    fun setup_givenProcessVisible_expectFirstOpen() {
        givenProcessImportance(RunningAppProcessInfo.IMPORTANCE_VISIBLE)

        setupPlugin()

        trackedEvents shouldBeEqualTo listOf(installed, firstOpened, foregrounded)
    }

    @Test
    fun setup_givenOnlyForegroundService_expectNoOpen() {
        givenProcessImportance(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)

        setupPlugin()

        trackedEvents shouldBeEqualTo listOf(installed)
    }

    @Test
    fun setup_givenProcessCached_expectNoOpen() {
        givenProcessImportance(RunningAppProcessInfo.IMPORTANCE_CACHED)

        setupPlugin()

        trackedEvents shouldBeEqualTo listOf(installed)
    }
}
