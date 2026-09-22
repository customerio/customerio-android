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

    /** This composition. A scenario runs here if its header names this, or names every platform. */
    private const val PLATFORM = "android"

    /** A scenario that declares it runs on every composition, not just the one that recorded it. */
    private const val ANY = "any"

    /** The provenance values a header may declare. Anything else is a broken file, not a default. */
    private val KINDS = setOf("recorded", "authored")

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
                    "directory holding the $SUFFIX files — mobile-replay-harness/scenarios, " +
                    "not mobile-replay-harness — or unset it to use the checkout beside this repo."
            }
            return dir
        }
        // Resolved by walking up from the module's working directory, which Gradle sets to the
        // project dir. Six levels is deliberate slack: the checkout sits beside customerio-android.
        var dir: File? = from
        repeat(6) {
            dir?.resolve("mobile-replay-harness/scenarios")?.takeIf { it.isDirectory }?.let { return it }
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
     * How many recorded drives the last discovery found.
     *
     * Exists so a caller can ask "were there any drives?" without re-running discovery — calling
     * [recorded] a second time clears [unreadable], which would drop failures an earlier pass had
     * already collected.
     */
    var recordedCount: Int = 0
        private set

    /**
     * Every scenario in the corpus whose header says it belongs on this composition, paired with
     * the parsed scenario so a caller can filter further without re-reading the file.
     *
     * Two header fields are validated rather than defaulted, because both feed guards that fail
     * open when they are wrong. A `platform` naming neither composition nor [ANY] is a broken file,
     * not somebody else's drive. A `source.kind` that is missing or unrecognised is reported the
     * same way: defaulting it to `recorded` let an authored scenario with no `source` satisfy the
     * "did discovery find any drives?" guard on its own, which is the emptiness check inverted.
     */
    private fun discover(): List<Pair<File, Scenario>> {
        unreadable.clear()
        val dir = root ?: return emptyList<Pair<File, Scenario>>().also { recordedCount = 0 }
        val found = dir.listFiles { f: File -> f.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching { file to ScenarioLoader.load(file) }
                    .getOrElse { error ->
                        unreadable.add("${file.name}: $error")
                        null
                    }
            }
            ?.filter { (file, scenario) ->
                if (scenario.platform !in setOf(PLATFORM, "ios", ANY)) {
                    unreadable.add(
                        "${file.name}: header platform is \"${scenario.platform}\", " +
                            "expected \"android\", \"ios\" or \"any\""
                    )
                    return@filter false
                }
                if (scenario.sourceKind !in KINDS) {
                    unreadable.add(
                        "${file.name}: header source.kind is \"${scenario.sourceKind}\", " +
                            "expected \"recorded\" or \"authored\""
                    )
                    return@filter false
                }
                scenario.platform == PLATFORM || scenario.platform == ANY
            }
            .orEmpty()
        recordedCount = found.count { (_, scenario) -> scenario.isRecorded }
        return found
    }

    /** Everything a run grades: recorded drives and authored scenarios alike. */
    fun replayable(): List<File> = discover().map { it.first }

    /**
     * The recorded drives alone, without the authored scenarios.
     *
     * Separate from [replayable] because the two answer different questions. A guard asking "did
     * discovery find anything" against the combined list is satisfied by the authored files, which
     * carry no device and cannot detect a corpus path that resolved somewhere drive-less.
     */
    fun recorded(): List<File> = discover().filter { it.second.isRecorded }.map { it.first }
}
