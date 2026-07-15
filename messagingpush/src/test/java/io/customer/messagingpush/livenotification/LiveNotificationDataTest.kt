package io.customer.messagingpush.livenotification

import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationDataTest : IntegrationTest() {

    @Test
    fun segments_mapsActivityTypeAndFlatCounts() {
        val data = LiveNotificationData.Segments(
            header = "Order update",
            status = "On the way",
            segmentsTotal = 3,
            segmentsComplete = 1
        )

        data.activityType shouldBeEqualTo TemplateRegistry.SEGMENTS
        val fields = data.fields()
        fields["status"] shouldBeEqualTo "On the way"
        fields["header"] shouldBeEqualTo "Order update"
        // Flat integer segment counts (matches iOS content-state), not a nested progress object.
        fields["segmentsTotal"] shouldBeEqualTo 3
        fields["segmentsComplete"] shouldBeEqualTo 1
        // Unset optional fields are present as null; the manager omits them from the envelope.
        fields["substatus"].shouldBeNull()
        fields["trailingText"].shouldBeNull()
    }

    @Test
    fun segments_splitsStaticAttributesFromDynamicContentState() {
        val data = LiveNotificationData.Segments(
            header = "Order update",
            status = "On the way",
            substatus = "For Alex",
            segmentsTotal = 3,
            segmentsComplete = 1,
            trailingText = "5 min"
        )

        // Static (attributes): header only.
        data.attributes().containsKey("header").shouldBeTrue()
        data.attributes().containsKey("status").shouldBeFalse()

        // Dynamic (contentState): status, substatus, flat counts, trailingText.
        val contentState = data.contentState()
        contentState["status"] shouldBeEqualTo "On the way"
        contentState["substatus"] shouldBeEqualTo "For Alex"
        contentState["segmentsTotal"] shouldBeEqualTo 3
        contentState["segmentsComplete"] shouldBeEqualTo 1
        contentState["trailingText"] shouldBeEqualTo "5 min"
        contentState.containsKey("header").shouldBeFalse()
    }

    @Test
    fun countdownTimer_mapsActivityTypeAndSplitsAttributes() {
        val data = LiveNotificationData.CountdownTimer(
            header = "Limited time",
            title = "Flash sale ends in",
            statusMessage = "Hurry!",
            endTime = 1700000000L
        )

        data.activityType shouldBeEqualTo TemplateRegistry.COUNTDOWN_TIMER

        // Static (attributes): header only.
        data.attributes().containsKey("header").shouldBeTrue()
        data.attributes().containsKey("title").shouldBeFalse()

        // Dynamic (contentState): title, statusMessage, endTime.
        val contentState = data.contentState()
        contentState["title"] shouldBeEqualTo "Flash sale ends in"
        contentState["statusMessage"] shouldBeEqualTo "Hurry!"
        contentState["endTime"] shouldBeEqualTo 1700000000L
    }

    @Test
    fun countdownTimer_optionalFieldsAreNull() {
        val data = LiveNotificationData.CountdownTimer(
            header = "Limited time",
            title = "Done"
        )

        val contentState = data.contentState()
        contentState["statusMessage"].shouldBeNull()
        contentState["endTime"].shouldBeNull()
    }
}
