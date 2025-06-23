package io.customer.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This test class benchmarks the speed of app startup.
 * Run this benchmark to verify how effective a Baseline Profile is.
 * It does this by comparing [CompilationMode.None], which represents the app with no Baseline
 * Profiles optimizations, and [CompilationMode.Partial], which uses Baseline Profiles.
 *
 * Run this benchmark to see startup measurements and captured system traces for verifying
 * the effectiveness of your Baseline Profiles. You can run it directly from Android
 * Studio as an instrumentation test, or run all benchmarks for a variant, for example benchmarkRelease,
 * with this Gradle task:
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * ```
 *
 * You should run the benchmarks on a physical device, not an Android emulator, because the
 * emulator doesn't represent real world performance and shares system resources with its host.
 *
 * For more information, see the [Macrobenchmark documentation](https://d.android.com/macrobenchmark#create-macrobenchmark)
 * and the [instrumentation arguments documentation](https://d.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args).
 **/
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone() =
        benchmark(CompilationMode.None())

    @Test
    fun startupCompilationPartial() =
        benchmark(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun startupCompilationFull() =
        benchmark(CompilationMode.Full())

    @Test
    fun startupColdCompilationNone() =
        benchmarkColdStartup(CompilationMode.None())

    @Test
    fun startupColdCompilationPartial() =
        benchmarkColdStartup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun startupWarmCompilationNone() =
        benchmarkWarmStartup(CompilationMode.None())

    @Test
    fun startupWarmCompilationPartial() =
        benchmarkWarmStartup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun benchmark(compilationMode: CompilationMode) {
        rule.measureRepeated(
            packageName = getTargetPackage(),
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = {
                pressHome()
            },
            measureBlock = {
                startActivityAndWait()
                device.waitForIdle()
            }
        )
    }

    private fun benchmarkColdStartup(compilationMode: CompilationMode) {
        rule.measureRepeated(
            packageName = getTargetPackage(),
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                pressHome()
                killProcess()
            },
            measureBlock = {
                startActivityAndWait()
                device.waitForIdle(2000)
            }
        )
    }

    private fun benchmarkWarmStartup(compilationMode: CompilationMode) {
        rule.measureRepeated(
            packageName = getTargetPackage(),
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                startActivityAndWait()
                pressHome()
            },
            measureBlock = {
                startActivityAndWait()
            }
        )
    }

    private fun getTargetPackage(): String {
        return InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: throw Exception("targetAppId not passed as instrumentation runner arg")
    }
}
