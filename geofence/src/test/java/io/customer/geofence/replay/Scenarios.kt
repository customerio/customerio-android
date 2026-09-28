package io.customer.geofence.replay

import java.io.File

/**
 * The recorded drives, which live outside this repo.
 *
 * `mobile-replay-harness` is a separate private checkout: the captures carry real coordinates and
 * fence names, so they must not be committed here. Leave it beside this repo, or point
 * `CIO_GEOFENCE_SCENARIOS` at the directory holding the `.scenario.ndjson` files
 * (`mobile-replay-harness/scenarios`, not its parent). Tests guard with
 * `Assume.assumeTrue(Scenarios.isAvailable)` so an absent corpus reports as skipped, not passed.
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
     * Where the drives are, given an override and a directory to walk up from.
     *
     * An override that is not a directory throws: null would read as "no corpus" and every test
     * would skip. Blank counts as unset. Separate from [root] so it can be tested, since [root]
     * reads the environment once.
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
        // Walks up from the working directory (Gradle sets it to the module dir): that directory
        // and five parents. The checkout normally sits beside the repo, two levels up.
        var dir: File? = from
        repeat(6) {
            dir?.resolve("mobile-replay-harness/scenarios")?.takeIf { it.isDirectory }?.let { return it }
            dir = dir?.parentFile
        }
        return null
    }

    val isAvailable: Boolean get() = root != null

    /**
     * Scenario files on disk that could not be read or have an invalid header, so a test can fail
     * on them instead of the suite silently replaying fewer drives. Cleared by each discovery.
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
     * How many recorded drives the last discovery found, readable without another discovery (which
     * would clear [unreadable]).
     */
    var recordedCount: Int = 0
        private set

    /**
     * Every scenario whose header puts it on this composition, paired with its parsed form.
     *
     * `platform` and `source.kind` are validated rather than defaulted because both feed guards
     * that fail open when wrong: an unknown value goes to [unreadable].
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
     * The recorded drives alone. A "found any drives" guard needs this rather than [replayable],
     * which authored files alone can satisfy.
     */
    fun recorded(): List<File> = discover().filter { it.second.isRecorded }.map { it.first }
}
