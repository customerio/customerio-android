package io.customer.tracking.migration.util

import io.customer.base.extenstions.getUnixTimestamp
import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.RobolectricTest
import io.customer.tracking.migration.request.MigrationTask
import io.customer.tracking.migration.testutils.data.DeletePushNotificationQueueTaskData
import io.customer.tracking.migration.testutils.data.IdentifyProfileQueueTaskData
import io.customer.tracking.migration.testutils.data.InAppDelivery
import io.customer.tracking.migration.testutils.data.PushMetric
import io.customer.tracking.migration.testutils.data.RegisterPushNotificationQueueTaskData
import io.customer.tracking.migration.testutils.data.TrackEventQueueTaskData
import io.customer.tracking.migration.testutils.extensions.findJSONObjectAtPath
import io.customer.tracking.migration.testutils.extensions.shouldMatchTo
import io.mockk.every
import io.mockk.mockkConstructor
import java.util.Date
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JsonAdapterTest : RobolectricTest() {
    private val jsonAdapter = JsonAdapter()
    private val mockedTime: Long
    private val mockedTimestamp: Long

    init {
        val mockedDate = Date()

        mockedTime = mockedDate.time
        mockedTimestamp = mockedDate.getUnixTimestamp()
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        mockkConstructor(Date::class)
        every { anyConstructed<Date>().time } returns mockedTime
    }

    @Test
    fun parse_givenIdentifyProfileQueueTaskData_expectIdentifyProfile() {
        val queueTask = IdentifyProfileQueueTaskData.CustomProperties.encodedJson()

        val result = decode<MigrationTask.IdentifyProfile>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenIdentifyProfileQueueTaskDataWithoutAttributes_expectIdentifyProfile() {
        val queueTask = IdentifyProfileQueueTaskData.EmptyProperties.encodedJson()

        val result = decode<MigrationTask.IdentifyProfile>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeEvent_expectTrackEvent() {
        val queueTask = TrackEventQueueTaskData.Event.CustomProperties.encodedJson()

        val result = decode<MigrationTask.TrackEvent>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeEventWithoutAttributes_expectTrackEvent() {
        val queueTask = TrackEventQueueTaskData.Event.EmptyProperties.encodedJson()

        val result = decode<MigrationTask.TrackEvent>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeEventEmptyTimestamp_expectTrackEvent() {
        val queueTask = TrackEventQueueTaskData.Event.NullTimestamp.encodedJson()

        val result = decode<MigrationTask.TrackEvent>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeScreen_expectTrackEvent() {
        val queueTask = TrackEventQueueTaskData.Screen.CustomProperties.encodedJson()

        val result = decode<MigrationTask.TrackEvent>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeScreenWithoutAttributes_expectTrackEvent() {
        val queueTask = TrackEventQueueTaskData.Screen.EmptyProperties.encodedJson()

        val result = decode<MigrationTask.TrackEvent>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeScreenEmptyTimestamp_expectTrackEvent() {
        val queueTask = TrackEventQueueTaskData.Screen.NullTimestamp.encodedJson()

        val result = decode<MigrationTask.TrackEvent>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenMetric_expectTrackPushMetric() {
        val queueTask = PushMetric.Opened.encodedJson()

        val result = decode<MigrationTask.TrackPushMetric>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenDeliveryEvent_expectTrackDeliveryEvent() {
        val queueTask = InAppDelivery.Clicked.encodedJson()

        val result = decode<MigrationTask.TrackDeliveryEvent>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenDeliveryEventEmptyMetadata_expectTrackDeliveryEvent() {
        val queueTask = InAppDelivery.EmptyMetadata.encodedJson()

        val result = decode<MigrationTask.TrackDeliveryEvent>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenRegisterPushNotificationQueueTaskData_expectRegisterDeviceToken() {
        val queueTask = RegisterPushNotificationQueueTaskData.CustomProperties.encodedJson()

        val result = decode<MigrationTask.RegisterDeviceToken>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenRegisterPushNotificationQueueTaskDataIncorrectLastUsed_expectRegisterDeviceToken() {
        val queueTask = RegisterPushNotificationQueueTaskData.InvalidLastUsed.encodedJson()

        val result = decode<MigrationTask.RegisterDeviceToken>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenRegisterPushNotificationQueueTaskDataEmptyLastUsed_expectRegisterDeviceToken() {
        val queueTask = RegisterPushNotificationQueueTaskData.NullLastUsed.encodedJson()

        val result = decode<MigrationTask.RegisterDeviceToken>(queueTask)

        result shouldMatchTo queueTask.data
    }

    @Test
    fun parse_givenDeletePushNotificationQueueTaskData_expectDeletePushToken() {
        val queueTask = DeletePushNotificationQueueTaskData.ValidToken.encodedJson()

        val result = decode<MigrationTask.DeletePushToken>(queueTask)

        result shouldMatchTo queueTask.data
    }

    /**
     * Extension functions for asserting the decoded MigrationTask.
     * These functions simplify the assertion of the decoded MigrationTask.
     */

    private infix fun MigrationTask.IdentifyProfile.shouldMatchTo(expected: JSONObject) {
        timestamp shouldBeEqualTo mockedTimestamp
        identifier shouldBeEqualTo expected.getString("identifier")
        attributes shouldMatchTo expected.getJSONObject("attributes")
    }

    private infix fun MigrationTask.TrackEvent.shouldMatchTo(expected: JSONObject) {
        val expectedEvent = expected.findJSONObjectAtPath("event")

        timestamp shouldBeEqualTo expectedEvent.optLong("timestamp", mockedTimestamp)
        identifier shouldBeEqualTo expected.getString("identifier")
        event shouldBeEqualTo expectedEvent.getString("name")
        type shouldBeEqualTo expectedEvent.getString("type")
        properties shouldMatchTo expectedEvent.getJSONObject("data")
    }

    private infix fun MigrationTask.TrackPushMetric.shouldMatchTo(expected: JSONObject) {
        val expectedDeliveryId = expected.getString("delivery_id")

        timestamp shouldBeEqualTo expected.getLong("timestamp")
        identifier shouldBeEqualTo expectedDeliveryId
        deliveryId shouldBeEqualTo expectedDeliveryId
        deviceToken shouldBeEqualTo expected.getString("device_id")
        event shouldBeEqualTo expected.getString("event")
    }

    private infix fun MigrationTask.TrackDeliveryEvent.shouldMatchTo(expected: JSONObject) {
        val expectedPayload = expected.findJSONObjectAtPath("payload")
        val expectedDeliveryId = expectedPayload.getString("delivery_id")

        timestamp shouldBeEqualTo expectedPayload.getLong("timestamp")
        identifier shouldBeEqualTo expectedDeliveryId
        deliveryType shouldBeEqualTo expected.getString("type")
        deliveryId shouldBeEqualTo expectedDeliveryId
        event shouldBeEqualTo expectedPayload.getString("event")
        metadata shouldMatchTo expectedPayload.getJSONObject("metadata")
    }

    private infix fun MigrationTask.RegisterDeviceToken.shouldMatchTo(expected: JSONObject) {
        val expectedDevice = expected.findJSONObjectAtPath("device")

        timestamp shouldBeEqualTo mockedTimestamp
        identifier shouldBeEqualTo expected.getString("profileIdentified")
        token shouldBeEqualTo expectedDevice.getString("id")
        platform shouldBeEqualTo expectedDevice.getString("platform")
        lastUsed shouldBeEqualTo expectedDevice.optLong("last_used", expectedDevice.optLong("lastUsed", mockedTimestamp))
        attributes shouldMatchTo expectedDevice.getJSONObject("attributes")
    }

    private infix fun MigrationTask.DeletePushToken.shouldMatchTo(expected: JSONObject) {
        timestamp shouldBeEqualTo mockedTimestamp
        identifier shouldBeEqualTo expected.getString("profileIdentified")
        token shouldBeEqualTo expected.getString("deviceToken")
    }

    /**
     * Extracts 'data' JSONObject from given JSONObject.
     * All queue tasks have 'data' key that contains the actual task json.
     */
    private val JSONObject.data: JSONObject get() = findJSONObjectAtPath("data")

    /**
     * Decodes given JSONObject to MigrationTask using implementation from
     * tracking-migration module.
     */
    private inline fun <reified T : MigrationTask> JsonAdapterTest.decode(queueTask: JSONObject): T {
        val result = jsonAdapter.parseMigrationTask(queueTask).fold(
            onSuccess = { result -> result.shouldNotBeNull().shouldBeInstanceOf<T>() },
            onFailure = { ex -> throw ex }
        )
        return result
    }
}
