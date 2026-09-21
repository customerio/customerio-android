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

    internal const val OVERRIDE = "CIO_GEOFENCE_SCENARIOS"

    val root: File? by lazy { resolve(System.getenv(OVERRIDE)) }

    /**
     * Where the drives are, given an override and somewhere to start walking from.
     *
     * Split out of [root] so the override rules can be tested: [root] reads the environment once
     * and caches it, which no test can arrange.
     *
     * An override that names something other than a directory **throws**. Resolving it to `null`
     * made a typo or a moved checkout indistinguishable from "no corpus here" — every caller
     * `assumeTrue(isAvailable)`, so discovery and all the replays skipped and the suite went green
     * having replayed nothing, which is the one outcome this harness exists to refuse.
     *
     * Blank is treated as unset rather than as an error: it names no path, and an environment that
     * exports the variable empty is not asserting where the corpus is.
     */
    internal fun resolve(override: String?, from: File = File("").absoluteFile): File? {
        override?.takeIf { it.isNotBlank() }?.let { path ->
            val dir = File(path)
            check(dir.isDirectory) {
                "$OVERRIDE is set to \"$path\", which is not a directory. Point it at the " +
                    "directory holding the $SUFFIX files — geofence-scenarios/recorded, not " +
                    "geofence-scenarios — or unset it to use the checkout beside this repo."
            }
            return dir
        }
        // Resolved by walking up from the module's working directory, which Gradle sets to the
        // project dir. Six levels is deliberate slack: the checkout sits beside customerio-android.
        var dir: File? = from
        repeat(6) {
            dir?.resolve("geofence-scenarios/recorded")?.takeIf { it.isDirectory }?.let { return it }
            dir = dir?.parentFile
        }
        return null
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
    /** Everything a run grades: the recorded drives plus the authored scenarios. */
    fun replayable(): List<File> = recorded() + conformance()

    /**
     * How many recorded drives the last [replayable] or [recorded] call discovered.
     *
     * Exists so a caller can ask "were there any drives?" without re-running discovery — calling
     * [recorded] a second time clears [unreadable], which would drop the conformance failures the
     * first pass had already collected.
     */
    var recordedCount: Int = 0
        private set

    /**
     * The recorded drives alone, without the authored conformance scenarios.
     *
     * Separate from [replayable] because the two answer different questions. A guard asking "did
     * discovery find anything" against the combined list is satisfied by the authored files, which
     * resolve from `root.parent/conformance` — so an override pointing at any drive-less sibling of
     * `recorded/` still looks healthy while grading zero drives.
     */
    fun recorded(): List<File> {
        unreadable.clear()
        val dir = root ?: return emptyList()
        return dir.listFiles { f: File -> f.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            ?.filter { file ->
                readable(file) { scenario ->
                    if (scenario.platform !in setOf("android", "ios")) {
                        // Reported, not filtered. Only a *load* failure was recorded before, so a
                        // misspelled `platfrom` key — or a stray `Android` — parsed cleanly,
                        // defaulted to `unknown`, and vanished from the run with nothing said.
                        unreadable.add("${file.name}: header platform is \"${scenario.platform}\", expected \"android\" or \"ios\"")
                        return@readable false
                    }
                    scenario.platform == "android"
                }
            }
            .orEmpty()
            .also { recordedCount = it.size }
    }
}
