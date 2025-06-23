package io.customer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
 * ./gradlew :samples.kotlin_compose:generateReleaseBaselineProfile
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
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = getTargetPackage(),
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()

            device.waitForIdle(2000)

            // Wait for app to fully load
            device.waitForIdle()
        }
    }

    @Test
    fun generateStartupProfile() {
        rule.collect(
            packageName = getTargetPackage(),
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle(3000)
        }
    }

    @Test
    fun generateUserJourneyProfile() {
        rule.collect(
            packageName = getTargetPackage(),
            includeInStartupProfile = false
        ) {
            pressHome()
            startActivityAndWait()

            // Wait for initial load
            device.waitForIdle(2000)

            // Simulate user interactions that are common in your app
            // This helps optimize the most frequently used code paths
            repeat(3) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight / 2,
                    device.displayWidth / 2,
                    device.displayHeight / 4,
                    50
                )
                device.waitForIdle(1000)
            }
        }
    }

    private fun getTargetPackage(): String {
        return InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: throw Exception("targetAppId not passed as instrumentation runner arg")
    }
}
