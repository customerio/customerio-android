package io.customer.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
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

fun MacrobenchmarkScope.exploreJavaLayoutApp() = device.apply {
    // Customer.io SDK baseline profile generation - focus only on SDK startup
    waitForIdle()

    // Just let the app start and SDK initialize
    // This captures the core SDK startup code paths without UI interactions
}
