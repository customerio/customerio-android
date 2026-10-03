package io.customer.geofence.replay

import java.io.File

/**
 * Drives live in the private `mobile-replay-harness` checkout; they carry real coordinates, so never
 * commit them here. Keep it beside this repo or point `CIO_GEOFENCE_SCENARIOS` at its `scenarios`
 * directory. Tests `assumeTrue(isAvailable)`, so an absent corpus reports as skipped, not passed.
 */
internal object Scenarios {

    private const val SUFFIX = ".scenario.ndjson"

    private const val PLATFORM = "android"

    private const val ANY = "any"

    private val KINDS = setOf("recorded", "authored")

    internal const val OVERRIDE = "CIO_GEOFENCE_SCENARIOS"

    val root: File? by lazy { resolve(System.getenv(OVERRIDE)) }

    /**
     * A non-directory override throws, since null would read as "no corpus" and every test would
     * skip. Blank counts as unset.
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
        // Gradle runs tests from the module dir; the checkout normally sits beside the repo, two
        // levels up.
        var dir: File? = from
        repeat(6) {
            dir?.resolve("mobile-replay-harness/scenarios")?.takeIf { it.isDirectory }?.let { return it }
            dir = dir?.parentFile
        }
        return null
    }

    val isAvailable: Boolean get() = root != null

    /** Files that failed to load or validate, so a test can fail on them. Cleared by each discovery. */
    val unreadable = mutableListOf<String>()

    private fun readable(file: File, predicate: (Scenario) -> Boolean): Boolean =
        runCatching { predicate(ScenarioLoader.load(file)) }
            .getOrElse { error ->
                unreadable.add("${file.name}: $error")
                false
            }

    /**
     * Recorded drives the last discovery found, readable without another one (which clears
     * [unreadable]).
     */
    var recordedCount: Int = 0
        private set

    /**
     * `platform` and `source.kind` are validated, not defaulted: both feed guards that fail open
     * when wrong.
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

    /** A "found any drives" guard needs this: authored files alone can satisfy [replayable]. */
    fun recorded(): List<File> = discover().filter { it.second.isRecorded }.map { it.first }
}
