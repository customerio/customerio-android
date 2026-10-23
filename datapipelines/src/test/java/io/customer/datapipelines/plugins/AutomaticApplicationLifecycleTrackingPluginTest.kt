package io.customer.datapipelines.plugins

import android.app.Activity
import android.app.Application
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test

class AutomaticApplicationLifecycleTrackingPluginTest : JUnitTest() {

    private val mockApplication = mockk<Application>(relaxed = true)
    private var isAppInForeground = false
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    private val subject = AutomaticApplicationLifecycleTrackingPlugin(
        application = mockApplication,
        appVersionProvider = { AppVersion(version = "1.0.0", build = "100") },
        isAppInForeground = { isAppInForeground }
    )

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    private val trackedEvents: List<Pair<String, JsonObject>>
        get() = outputReaderPlugin.trackEvents.map { it.event to it.properties }

    private fun clearTrackedEvents() = outputReaderPlugin.allEvents.clear()

    private fun givenStoredVersion(version: String?, build: String?) = runTest {
        version?.let { analytics.storage.write(Storage.Constants.AppVersion, it) }
        build?.let { analytics.storage.write(Storage.Constants.AppBuild, it) }
    }

    private fun mockActivity(isChangingConfigurations: Boolean = false): Activity = mockk<Activity>(relaxed = true).apply {
        every { this@apply.isChangingConfigurations } returns isChangingConfigurations
    }

    private fun Activity.simulateStart() {
        subject.onActivityCreated(this, null)
        subject.onActivityStarted(this)
        subject.onActivityResumed(this)
    }

    private fun Activity.simulateStop() {
        subject.onActivityPaused(this)
        subject.onActivityStopped(this)
    }

    private fun Activity.simulateStopAndDestroy() {
        simulateStop()
        subject.onActivityDestroyed(this)
    }

    private val installed = "Application Installed" to buildJsonObject {
        put("version", "1.0.0")
        put("build", "100")
    }
    private val firstOpened = "Application Opened" to buildJsonObject {
        put("version", "1.0.0")
        put("build", "100")
        put("from_background", false)
    }
    private val openedFromBackground = "Application Opened" to buildJsonObject {
        put("from_background", true)
    }
    private val foregrounded = "Application Foregrounded" to emptyJsonObject
    private val backgrounded = EventNames.APPLICATION_BACKGROUNDED to emptyJsonObject

    //region Setup

    @Test
    fun setup_expectActivityLifecycleCallbacksRegistered() {
        subject.setup(analytics)

        verify(exactly = 1) { mockApplication.registerActivityLifecycleCallbacks(subject) }
    }

    @Test
    fun setup_givenNoStoredVersionAndAppInBackground_expectOnlyInstalledTracked() {
        subject.setup(analytics)

        trackedEvents shouldBeEqualTo listOf(installed)
    }

    @Test
    fun setup_givenNoStoredVersionAndAppInForeground_expectInstalledOpenedAndForegroundedTracked() {
        isAppInForeground = true

        subject.setup(analytics)

        trackedEvents shouldBeEqualTo listOf(installed, firstOpened, foregrounded)
    }

    @Test
    fun setup_givenSameBuildStored_expectNoInstallOrUpdateTracked() {
        givenStoredVersion(version = "1.0.0", build = "100")

        subject.setup(analytics)

        trackedEvents shouldBeEqualTo emptyList()
    }

    @Test
    fun setup_givenDifferentBuildStored_expectUpdatedTrackedWithPreviousVersion() {
        givenStoredVersion(version = "0.9", build = "90")

        subject.setup(analytics)

        trackedEvents shouldBeEqualTo listOf(
            "Application Updated" to buildJsonObject {
                put("version", "1.0.0")
                put("build", "100")
                put("previous_version", "0.9")
                put("previous_build", "90")
            }
        )
    }

    @Test
    fun setup_givenOnlyLegacyBuildStored_expectUpdatedTrackedWithLegacyBuild() = runTest {
        analytics.storage.write(Storage.Constants.LegacyAppBuild, "90")

        subject.setup(analytics)

        trackedEvents shouldBeEqualTo listOf(
            "Application Updated" to buildJsonObject {
                put("version", "1.0.0")
                put("build", "100")
                put("previous_version", null as String?)
                put("previous_build", "90")
            }
        )
    }

    @Test
    fun setup_expectCurrentVersionStored() {
        subject.setup(analytics)

        analytics.storage.read(Storage.Constants.AppVersion) shouldBeEqualTo "1.0.0"
        analytics.storage.read(Storage.Constants.AppBuild) shouldBeEqualTo "100"
    }

    @Test
    fun setup_givenVersionNameMissing_expectOnlyBuildStored() {
        val subject = AutomaticApplicationLifecycleTrackingPlugin(
            application = mockApplication,
            appVersionProvider = { AppVersion(version = null, build = "100") },
            isAppInForeground = { false }
        )

        subject.setup(analytics)

        analytics.storage.read(Storage.Constants.AppVersion).shouldBeNull()
        analytics.storage.read(Storage.Constants.AppBuild) shouldBeEqualTo "100"
    }

    //endregion
    //region Initialized before first Activity

    @Test
    fun firstActivityStarted_expectFirstOpenedAndForegroundedTracked() {
        subject.setup(analytics)
        clearTrackedEvents()

        mockActivity().simulateStart()

        trackedEvents shouldBeEqualTo listOf(firstOpened, foregrounded)
    }

    @Test
    fun lastActivityStopped_expectBackgroundedTrackedImmediately() {
        val activity = mockActivity()
        subject.setup(analytics)
        activity.simulateStart()
        clearTrackedEvents()

        activity.simulateStop()

        trackedEvents shouldBeEqualTo listOf(backgrounded)
    }

    @Test
    fun activityStartedAfterBackground_expectOpenedFromBackgroundAndForegroundedTracked() {
        val activity = mockActivity()
        subject.setup(analytics)
        activity.simulateStart()
        activity.simulateStop()
        clearTrackedEvents()

        subject.onActivityStarted(activity)
        subject.onActivityResumed(activity)

        trackedEvents shouldBeEqualTo listOf(openedFromBackground, foregrounded)
    }

    @Test
    fun repeatedBackgroundAndForeground_expectEveryTransitionTracked() {
        val activity = mockActivity()
        subject.setup(analytics)
        activity.simulateStart()
        clearTrackedEvents()

        repeat(3) {
            activity.simulateStop()
            subject.onActivityStarted(activity)
            subject.onActivityResumed(activity)
        }

        trackedEvents shouldBeEqualTo List(3) { listOf(backgrounded, openedFromBackground, foregrounded) }.flatten()
    }

    @Test
    fun activityPausedAndResumedWithoutStop_expectNoEventsAndLaterBackgroundTracked() {
        // e.g. a runtime permission dialog or a quick app switch
        val activity = mockActivity()
        subject.setup(analytics)
        activity.simulateStart()
        clearTrackedEvents()

        repeat(3) {
            subject.onActivityPaused(activity)
            subject.onActivityResumed(activity)
        }
        trackedEvents shouldBeEqualTo emptyList()

        activity.simulateStop()
        trackedEvents shouldBeEqualTo listOf(backgrounded)
    }

    @Test
    fun translucentActivityShownAndDismissed_expectNoEventsAndLaterBackgroundTracked() {
        // e.g. an in-app message modal: the host Activity is paused but not stopped
        val host = mockActivity()
        val overlay = mockActivity()
        subject.setup(analytics)
        host.simulateStart()
        clearTrackedEvents()

        subject.onActivityPaused(host)
        overlay.simulateStart()
        subject.onActivityPaused(overlay)
        subject.onActivityResumed(host)
        subject.onActivityStopped(overlay)
        subject.onActivityDestroyed(overlay)
        trackedEvents shouldBeEqualTo emptyList()

        host.simulateStop()
        trackedEvents shouldBeEqualTo listOf(backgrounded)
    }

    @Test
    fun navigatingBetweenActivities_expectNoEvents() {
        val first = mockActivity()
        val second = mockActivity()
        subject.setup(analytics)
        first.simulateStart()
        clearTrackedEvents()

        subject.onActivityPaused(first)
        second.simulateStart()
        subject.onActivityStopped(first)
        subject.onActivityPaused(second)
        subject.onActivityStarted(first)
        subject.onActivityResumed(first)
        subject.onActivityStopped(second)
        subject.onActivityDestroyed(second)

        trackedEvents shouldBeEqualTo emptyList()
    }

    @Test
    fun activityRecreatedForConfigurationChange_expectNoEvents() {
        val original = mockActivity()
        val recreated = mockActivity()
        subject.setup(analytics)
        original.simulateStart()
        clearTrackedEvents()

        every { original.isChangingConfigurations } returns true
        original.simulateStopAndDestroy()
        recreated.simulateStart()

        trackedEvents shouldBeEqualTo emptyList()
    }

    //endregion
    //region Initialized after first Activity started

    @Test
    fun lateInit_givenActivityAlreadyResumed_expectBackgroundedWhenItStops() {
        val activity = mockActivity()
        isAppInForeground = true
        subject.setup(analytics)
        clearTrackedEvents()

        activity.simulateStop()

        trackedEvents shouldBeEqualTo listOf(backgrounded)
    }

    @Test
    fun lateInit_givenActivityAlreadyResumed_expectNoEventsForLaterActivityCreation() {
        val activity = mockActivity()
        val overlay = mockActivity()
        isAppInForeground = true
        subject.setup(analytics)
        clearTrackedEvents()

        subject.onActivityPaused(activity)
        overlay.simulateStart()
        subject.onActivityPaused(overlay)
        subject.onActivityResumed(activity)
        subject.onActivityStopped(overlay)
        subject.onActivityDestroyed(overlay)

        trackedEvents shouldBeEqualTo emptyList()
    }

    @Test
    fun lateInit_givenHostPausedUnderOverlayBeforeInit_expectSingleBackgroundedOnLeavingApp() {
        val host = mockActivity()
        val overlay = mockActivity()
        isAppInForeground = true
        subject.setup(analytics)
        clearTrackedEvents()

        // Both were started before initialization; leaving the app stops both
        subject.onActivityPaused(overlay)
        subject.onActivityStopped(overlay)
        subject.onActivityStopped(host)

        trackedEvents shouldBeEqualTo listOf(backgrounded)
    }

    @Test
    fun lateInit_givenOverlayDismissedAfterInit_expectBackgroundedOnlyWhenHostStops() {
        val host = mockActivity()
        val overlay = mockActivity()
        isAppInForeground = true
        subject.setup(analytics)
        clearTrackedEvents()

        subject.onActivityPaused(overlay)
        subject.onActivityResumed(host)
        subject.onActivityStopped(overlay)
        trackedEvents shouldBeEqualTo emptyList()

        host.simulateStop()
        trackedEvents shouldBeEqualTo listOf(backgrounded)
    }

    @Test
    fun lateInit_givenActivityPausedButStillVisible_expectNoBackgroundedWhenAnotherActivityStops() {
        // e.g. split screen: the first Activity loses focus but stays started while another one comes and goes
        val first = mockActivity()
        val second = mockActivity()
        isAppInForeground = true
        subject.setup(analytics)
        clearTrackedEvents()

        subject.onActivityPaused(first)
        second.simulateStart()
        second.simulateStopAndDestroy()
        trackedEvents shouldBeEqualTo emptyList()

        subject.onActivityStopped(first)
        trackedEvents shouldBeEqualTo listOf(backgrounded)
    }

    @Test
    fun lateInit_givenActivityAlreadyResumed_expectOpenedFromBackgroundOnReturn() {
        val activity = mockActivity()
        isAppInForeground = true
        subject.setup(analytics)
        activity.simulateStop()
        clearTrackedEvents()

        subject.onActivityStarted(activity)
        subject.onActivityResumed(activity)

        trackedEvents shouldBeEqualTo listOf(openedFromBackground, foregrounded)
    }

    //endregion
}
