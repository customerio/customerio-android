package io.customer.geofence.replay

import java.io.File

/**
 * The recorded drives, which live outside this repo.
 *
 * `geofence-scenarios/` is a separate private checkout: the captures carry real coordinates and
 * real business fence names, so nothing here may be committed alongside the SDK. Leave it beside
 * this repo, or point `CIO_GEOFENCE_SCENARIOS` at the directory holding the `.scenario.ndjson`
 * files — `geofence-scenarios/recorded`, not `geofence-scenarios`. The sibling fallback appends
 * `recorded` for you; the override does not, and one level too high finds no drives and skips the
 * whole suite while looking exactly like a clean run.
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
     * Scenario files present on disk that could not be read.
     *
     * Discovery filters, so it cannot report a failure itself — and a bare `runCatching{}.getOrDefault(false)`
     * turns an unparseable drive into a drive that was never there. The suite then passes having
     * replayed fewer drives than exist, which is the exact failure this harness is built to refuse.
     * Collected here and asserted by a test that does run. Cleared at the start of each
     * [replayable] call: this is an `object`, discovery runs once per test that asks for it, and an
     * accumulating list reports the same broken file once per caller.
     */
    val unreadable = mutableListOf<String>()

    /** Applies [predicate] to a scenario, recording the file instead of dropping it if it fails to load. */
    private fun readable(file: File, predicate: (Scenario) -> Boolean): Boolean =
        runCatching { predicate(ScenarioLoader.load(file)) }
            .getOrElse { error ->
                unreadable.add("${file.name}: $error")
                false
            }

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
            ?.filter { file -> readable(file) { it.isConformance } }
            ?: emptyList()

    /**
     * Every drive this harness can replay, discovered from disk rather than listed, so adding a
     * drive is dropping in a file.
     *
     * The platform filter reads each header rather than trusting the filename: this is the Android
     * composition, and an iOS capture reports one fence per callback where GMS batches several.
     */
    fun replayable(): List<File> {
        unreadable.clear()
        val dir = root ?: return emptyList()
        return dir.listFiles { f: File -> f.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            ?.filter { file -> readable(file) { it.platform == "android" } }
            .orEmpty() + conformance()
    }
}
