package io.customer.datapipelines.handoff

import java.io.File
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * Every call into Segment goes through [SegmentGateway], so a test that wraps the gateway sees all
 * of them. A direct `analytics.track(…)` in main sources skips it, and a replay would read that
 * event as never handed off. Plugin registration, configuration and storage calls are not hand-offs
 * and stay on [com.segment.analytics.kotlin.core.Analytics].
 */
class SegmentSeamLintTest {

    @Test
    fun mainSources_expectNoSegmentCallOutsideTheSeam() {
        val root = File("src/main/kotlin")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(sources.isNotEmpty()) { "no Kotlin sources under ${root.absolutePath}: the scan ran from the wrong directory" }

        val found = sources
            .filterNot { it.invariantSeparatorsPath.contains(SEAM) }
            .flatMap { file -> directCalls(file.readText()).map { "${file.relativeTo(root)}:$it" } }
        // The list names each call, so a failure says where to route it through the seam
        found shouldBeEqualTo emptyList()
    }

    @Test
    fun directCall_expectFlagged() {
        val source = """
            fun send() {
                analytics.track("Device Created or Updated")
                analytics?.identify(userId = "A", traits = traits, serializationStrategy = strategy)
                val id = analytics.userId()
            }
        """.trimIndent()

        directCalls(source) shouldBeEqualTo listOf(
            "2: analytics.track(",
            "3: analytics?.identify(",
            "4: analytics.userId("
        )
    }

    @Test
    fun commentsAndNonHandOffCalls_expectNotFlagged() {
        val source = """
            // analytics.track() would skip the log
            /** Must come after analytics.identify(), see [syncUserIdentified]. */
            fun setUp() {
                analytics.add(plugin)
                analytics.configuration.flushAt
                analytics.gateway().track("e1")
            }
        """.trimIndent()

        directCalls(source).shouldBeEmpty()
    }

    private companion object {
        // The gateway itself
        const val SEAM = "io/customer/datapipelines/handoff/"

        val DIRECT_CALL = Regex("""\banalytics\??\.(identify|track|screen|reset|alias|process|group|userId|anonymousId|traits)\s*[(<]""")

        /** `line: call` for each direct Segment call, comments skipped. */
        fun directCalls(source: String): List<String> = source.lines().flatMapIndexed { index, line ->
            val code = line.trimStart()
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@flatMapIndexed emptyList()
            DIRECT_CALL.findAll(code.substringBefore("//")).map { "${index + 1}: ${it.value}" }.toList()
        }
    }
}
