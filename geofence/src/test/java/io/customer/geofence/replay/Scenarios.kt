package io.customer.geofence.replay

import java.io.File

/**
 * The recorded drives, which live outside this repo.
 *
 * `geofence-scenarios/` is a separate private checkout: the captures carry real coordinates and
 * real business fence names, so nothing here may be committed alongside the SDK. Point
 * `CIO_GEOFENCE_SCENARIOS` at it, or leave it beside this repo.
 *
 * Tests that need it use `Assume.assumeTrue(Scenarios.isAvailable)` so they report as **skipped**
 * when absent. Returning early instead reports as *passed* — a green test that asserted nothing,
 * which is the failure mode this whole exercise exists to avoid.
 */
internal object Scenarios {

    private const val SUFFIX = ".scenario.ndjson"

    val root: File? by lazy {
        System.getenv("CIO_GEOFENCE_SCENARIOS")?.let { override ->
            return@lazy File(override).takeIf { it.isDirectory }
        }
        // Resolved by walking up from the module's working directory, which Gradle sets to the
        // project dir. Six levels is deliberate slack: the checkout sits beside customerio-android.
        var dir: File? = File("").absoluteFile
        repeat(6) {
            dir?.resolve("geofence-scenarios/recorded")?.takeIf { it.isDirectory }?.let { return@lazy it }
            dir = dir?.parentFile
        }
        null
    }

    val isAvailable: Boolean get() = root != null

    /**
     * Authored scenarios, beside `recorded/` rather than in it.
     *
     * EXPERIMENTAL. A recorded drive belongs to the OS that produced it — the same crossing fired
     * nine minutes apart across the 2026-09-11 fleet — so a shared file can only ever be one
     * somebody wrote. These are written against the vocabulary both platforms already share and
     * run on both, unfiltered by the header's `platform`.
     */
    private val conformanceRoot: File? by lazy {
        root?.parentFile?.resolve("conformance")?.takeIf { it.isDirectory }
    }

    /**
     * `expect` is checked rather than the directory: a file that has not opted in stays out, so
     * dropping a recorded drive in here by mistake cannot silently run against the wrong OS.
     */
    private fun conformance(): List<File> =
        conformanceRoot?.listFiles { f: File -> f.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            ?.filter { file -> runCatching { ScenarioLoader.load(file).isConformance }.getOrDefault(false) }
            ?: emptyList()

    /**
     * Every drive this harness can replay, discovered from disk rather than listed, so adding a
     * drive is dropping in a file.
     *
     * The platform filter reads each header rather than trusting the filename: this is the Android
     * composition, and an iOS capture reports one fence per callback where GMS batches several.
     */
    fun replayable(): List<File> {
        val dir = root ?: return emptyList()
        return dir.listFiles { f: File -> f.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            ?.filter { file ->
                runCatching { ScenarioLoader.load(file).platform == "android" }.getOrDefault(false)
            }
            .orEmpty() + conformance()
    }
}
