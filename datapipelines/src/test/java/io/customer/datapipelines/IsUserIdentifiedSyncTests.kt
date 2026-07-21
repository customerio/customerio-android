package io.customer.datapipelines

import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.JUnitTest
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

/**
 * Direct coverage for the synchronous `CustomerIO.isUserIdentified` mirror at the layer that owns
 * it. The messagingpush live-notification tests mock `isUserIdentified`, so they don't prove the
 * contract itself: it must flip true on the caller thread the instant `identify()` returns (before
 * the async `analytics.userId()` catches up), flip false the instant `clearIdentify()` returns, and
 * still reflect a session restored from persistence where neither was called this process.
 */
class IsUserIdentifiedSyncTests : JUnitTest() {

    @Test
    fun isUserIdentified_isTrueSynchronouslyAfterIdentify() {
        sdkInstance.isUserIdentified.shouldBeFalse()

        sdkInstance.identify(String.random)

        // No async wait: the mirror is set on the caller thread inside identify(), so a consumer
        // gating on it immediately after login (e.g. a live-notification start()) is not dropped.
        sdkInstance.isUserIdentified.shouldBeTrue()
    }

    @Test
    fun isUserIdentified_isFalseSynchronouslyAfterClearIdentify() {
        sdkInstance.identify(String.random)
        sdkInstance.isUserIdentified.shouldBeTrue()

        sdkInstance.clearIdentify()

        sdkInstance.isUserIdentified.shouldBeFalse()
    }

    @Test
    fun isUserIdentified_fallsBackToAnalyticsForRestoredSession() {
        // Cold launch: no identify() this process (the mirror stays unset), but analytics restored a
        // userId from persistence. isUserIdentified must reflect the restored user via the fallback.
        analytics.identify(String.random)

        sdkInstance.isUserIdentified.shouldBeTrue()
    }
}
