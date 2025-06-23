package io.customer.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal const val TIMEOUT = 15_000L

/**
 * This test class generates a basic startup baseline profile for the target package.
 *
 * We recommend you start with this but add important user flows to the profile to improve their performance.
 * Refer to the [baseline profile documentation](https://d.android.com/topic/performance/baselineprofiles)
 * for more information.
 *
 * You can run the generator with the "Generate Baseline Profile" run configuration in Android Studio or
 * the equivalent `generateBaselineProfile` gradle task:
 * ```
 * ./gradlew :samples:java_layout:generateReleaseBaselineProfile
 * ```
 * The run configuration runs the Gradle task and applies filtering to run only the generators.
 *
 * Check [documentation](https://d.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args)
 * for more information about available instrumentation arguments.
 *
 * After you run the generator, you can verify the improvements running the [StartupBenchmarks] benchmark.
 *
 * When using this class to generate a baseline profile, only API 33+ or rooted API 28+ are supported.
 *
 * The minimum required version of androidx.benchmark to generate a baseline profile is 1.2.0.
 **/
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(
            packageName = getTargetPackage(),
            stableIterations = 2,
            maxIterations = 8,
            includeInStartupProfile = true
        ) {
            // Start default activity for your app
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            // This block defines the app's critical user journey. Here we are interested in
            // optimizing for app startup. But you can also navigate and scroll through your most important UI.
            exploreJavaLayoutApp()
        }
    }

    private fun getTargetPackage(): String {
        return InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: throw IllegalArgumentException("targetAppId not passed as instrumentation runner arg")
    }
}

/**
 * Waits until an object with [selector] if visible on screen and returns the object.
 * If the element is not available in [timeout], throws [AssertionError]
 */
internal fun UiDevice.waitAndFindObject(selector: BySelector, timeout: Long = TIMEOUT): UiObject2 {
    if (!wait(Until.hasObject(selector), timeout)) {
        throw AssertionError("Element not found on screen in ${timeout}ms (selector=$selector)")
    }

    return findObject(selector)
}

fun MacrobenchmarkScope.exploreJavaLayoutApp() {
    // Wait for login screen to load
    device.wait(Until.hasObject(By.res("login_button")), TIMEOUT)
    device.waitForIdle()

    // CRITICAL: Random login triggers Customer.io identify() call
    // This exercises SDK initialization and user identification code paths
    device.waitAndFindObject(By.res("random_login_button"), TIMEOUT).click()
    device.waitForIdle()

    // CRITICAL: Exercise Customer.io tracking code paths
    // This pre-compiles event tracking, validation, and queuing logic
    // waitAndFindObject will wait for dashboard to load and SDK initialization
    device.waitAndFindObject(By.res("send_random_event_button"), TIMEOUT).click()
    device.waitForIdle()

    // CRITICAL: Exercise Customer.io attribute handling
    // This pre-compiles user/device attribute processing code
    device.waitAndFindObject(By.res("set_device_attributes_button"), TIMEOUT).click()
    device.waitForIdle()

    device.waitAndFindObject(By.res("set_profile_attributes_button"), TIMEOUT).click()
    device.waitForIdle()

    // These interactions ensure Customer.io SDK's core functionality is pre-compiled:
    // - User identification and authentication
    // - Event tracking and queuing
    // - Attribute processing and storage
    // Result: Faster SDK startup and better runtime performance
}
