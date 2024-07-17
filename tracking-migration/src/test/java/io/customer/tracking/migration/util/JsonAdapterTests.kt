package io.customer.tracking.migration.util

import com.squareup.moshi.Moshi
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.extensions.random
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.moshi.adapter.BigDecimalAdapter
import io.customer.sdk.data.moshi.adapter.CustomAttributesFactory
import io.customer.sdk.data.moshi.adapter.UnixDateAdapter
import io.customer.sdk.data.request.DeliveryEvent
import io.customer.sdk.data.request.DeliveryPayload
import io.customer.sdk.data.request.DeliveryType
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.Metric
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.queue.taskdata.DeletePushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.IdentifyProfileQueueTaskData
import io.customer.sdk.queue.taskdata.RegisterPushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.queue.type.QueueTask
import io.customer.sdk.queue.type.QueueTaskRunResults
import io.customer.tracking.migration.request.MigrationTask
import io.customer.tracking.migration.testutils.extensions.enumValue
import io.customer.tracking.migration.testutils.extensions.findJSONObjectAtPath
import io.customer.tracking.migration.testutils.extensions.shouldBeEmpty
import io.customer.tracking.migration.testutils.extensions.shouldMatchTo
import io.customer.tracking.migration.testutils.utils.DateStub
import io.customer.tracking.migration.testutils.utils.mock
import io.customer.tracking.migration.testutils.utils.unmock
import java.util.UUID
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JsonAdapterTests : RobolectricTest() {
    internal val jsonAdapter = JsonAdapter()
    internal val moshi = Moshi.Builder()
        .add(UnixDateAdapter())
        .add(BigDecimalAdapter())
        .add(CustomAttributesFactory())
        .build()

    private val sampleAttributes = mapOf(
        "color" to "green",
        "price" to 135,
        "imported" to false,
        "sizes" to listOf("Small", "Medium", "Large")
    )

    @Test
    fun parse_givenIdentifyProfileQueueTaskData_expectIdentifyProfile() {
        val givenDateStub = DateStub()
        val givenData = IdentifyProfileQueueTaskData(
            identifier = String.random,
            attributes = sampleAttributes
        )
        val givenJson = encode(
            type = "IdentifyProfile",
            data = givenData
        )

        val result = decode<MigrationTask.IdentifyProfile>(
            jsonObject = givenJson,
            dateStub = givenDateStub
        )

        result.timestamp shouldBeEqualTo givenDateStub.timestamp
        result.identifier shouldBeEqualTo givenData.identifier
        result.attributes shouldMatchTo givenData.attributes
    }

    @Test
    fun parse_givenIdentifyProfileQueueTaskDataWithoutAttributes_expectIdentifyProfile() {
        val givenDateStub = DateStub()
        val givenData = IdentifyProfileQueueTaskData(
            identifier = String.random,
            attributes = emptyMap()
        )
        val givenJson = encode(
            type = "IdentifyProfile",
            data = givenData
        )

        val result = decode<MigrationTask.IdentifyProfile>(
            jsonObject = givenJson,
            dateStub = givenDateStub
        )

        result.timestamp shouldBeEqualTo givenDateStub.timestamp
        result.identifier shouldBeEqualTo givenData.identifier
        result.attributes.shouldBeEmpty()
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeEvent_expectTrackEvent() {
        val givenEvent = Event(
            name = String.random,
            type = EventType.event,
            data = sampleAttributes,
            timestamp = DateStub().timestamp
        )
        val givenData = TrackEventQueueTaskData(
            identifier = String.random,
            event = givenEvent
        )
        val givenJson = encode(
            type = "TrackEvent",
            data = givenData
        )

        val result = decode<MigrationTask.TrackEvent>(givenJson)

        result.timestamp shouldBeEqualTo givenEvent.timestamp
        result.identifier shouldBeEqualTo givenData.identifier
        result.event shouldBeEqualTo givenEvent.name
        result.type.enumValue<EventType>() shouldBeEqualTo givenEvent.type
        result.properties shouldMatchTo givenEvent.data
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeEventWithoutAttributes_expectTrackEvent() {
        val givenEvent = Event(
            name = String.random,
            type = EventType.event,
            data = emptyMap(),
            timestamp = DateStub().timestamp
        )
        val givenData = TrackEventQueueTaskData(
            identifier = String.random,
            event = givenEvent
        )
        val givenJson = encode(
            type = "TrackEvent",
            data = givenData
        )

        val result = decode<MigrationTask.TrackEvent>(givenJson)

        result.timestamp shouldBeEqualTo givenEvent.timestamp
        result.identifier shouldBeEqualTo givenData.identifier
        result.event shouldBeEqualTo givenEvent.name
        result.type.enumValue<EventType>() shouldBeEqualTo givenEvent.type
        result.properties.shouldBeEmpty()
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeEventEmptyTimestamp_expectTrackEvent() {
        val givenDateStub = DateStub()
        val givenEvent = Event(
            name = String.random,
            type = EventType.event,
            data = emptyMap(),
            timestamp = null
        )
        val givenData = TrackEventQueueTaskData(
            identifier = String.random,
            event = givenEvent
        )
        val givenJson = encode(
            type = "TrackEvent",
            data = givenData
        )

        val result = decode<MigrationTask.TrackEvent>(
            jsonObject = givenJson,
            dateStub = givenDateStub
        )

        result.timestamp shouldBeEqualTo givenDateStub.timestamp
        result.identifier shouldBeEqualTo givenData.identifier
        result.event shouldBeEqualTo givenEvent.name
        result.type.enumValue<EventType>() shouldBeEqualTo givenEvent.type
        result.properties.shouldBeEmpty()
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeScreen_expectTrackEvent() {
        val givenEvent = Event(
            name = String.random,
            type = EventType.screen,
            data = sampleAttributes,
            timestamp = DateStub().timestamp
        )
        val givenData = TrackEventQueueTaskData(
            identifier = String.random,
            event = givenEvent
        )
        val givenJson = encode(
            type = "TrackEvent",
            data = givenData
        )

        val result = decode<MigrationTask.TrackEvent>(givenJson)

        result.timestamp shouldBeEqualTo givenEvent.timestamp
        result.identifier shouldBeEqualTo givenData.identifier
        result.event shouldBeEqualTo givenEvent.name
        result.type.enumValue<EventType>() shouldBeEqualTo givenEvent.type
        result.properties shouldMatchTo givenEvent.data
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeScreenWithoutAttributes_expectTrackEvent() {
        val givenEvent = Event(
            name = String.random,
            type = EventType.screen,
            data = emptyMap(),
            timestamp = DateStub().timestamp
        )
        val givenData = TrackEventQueueTaskData(
            identifier = String.random,
            event = givenEvent
        )
        val givenJson = encode(
            type = "TrackEvent",
            data = givenData
        )

        val result = decode<MigrationTask.TrackEvent>(givenJson)

        result.timestamp shouldBeEqualTo givenEvent.timestamp
        result.identifier shouldBeEqualTo givenData.identifier
        result.event shouldBeEqualTo givenEvent.name
        result.type.enumValue<EventType>() shouldBeEqualTo givenEvent.type
        result.properties.shouldBeEmpty()
    }

    @Test
    fun parse_givenTrackEventQueueTaskDataTypeScreenEmptyTimestamp_expectTrackEvent() {
        val givenDateStub = DateStub()
        val givenEvent = Event(
            name = String.random,
            type = EventType.screen,
            data = emptyMap(),
            timestamp = null
        )
        val givenData = TrackEventQueueTaskData(
            identifier = String.random,
            event = givenEvent
        )
        val givenJson = encode(
            type = "TrackEvent",
            data = givenData
        )

        val result = decode<MigrationTask.TrackEvent>(
            jsonObject = givenJson,
            dateStub = givenDateStub
        )

        result.timestamp shouldBeEqualTo givenDateStub.timestamp
        result.identifier shouldBeEqualTo givenData.identifier
        result.event shouldBeEqualTo givenEvent.name
        result.type.enumValue<EventType>() shouldBeEqualTo givenEvent.type
        result.properties.shouldBeEmpty()
    }

    @Test
    fun parse_givenMetric_expectTrackPushMetric() {
        val givenDateStub = DateStub()
        val givenData = Metric(
            deliveryID = String.random,
            deviceToken = String.random,
            event = MetricEvent.opened,
            timestamp = givenDateStub.date
        )
        val givenJson = encode(
            type = "TrackPushMetric",
            data = givenData
        )

        val result = decode<MigrationTask.TrackPushMetric>(givenJson)

        result.timestamp shouldBeEqualTo givenData.timestamp.getUnixTimestamp()
        result.identifier shouldBeEqualTo givenData.deliveryID
        result.deliveryId shouldBeEqualTo givenData.deliveryID
        result.deviceToken shouldBeEqualTo givenData.deviceToken
        result.event.enumValue<MetricEvent>() shouldBeEqualTo givenData.event
    }

    @Test
    fun parse_givenDeliveryEvent_expectTrackDeliveryEvent() {
        val givenDateStub = DateStub()
        val givenPayload = DeliveryPayload(
            deliveryID = String.random,
            event = MetricEvent.clicked,
            timestamp = givenDateStub.date,
            metaData = mapOf(
                "color" to "green",
                "price" to "135",
                "imported" to "false",
                "sizes" to "['S', 'M', 'L']"
            )
        )
        val givenData = DeliveryEvent(
            type = DeliveryType.in_app,
            payload = givenPayload
        )
        val givenJson = encode(
            type = "TrackDeliveryEvent",
            data = givenData
        )

        val result = decode<MigrationTask.TrackDeliveryEvent>(givenJson)

        result.timestamp shouldBeEqualTo givenPayload.timestamp.getUnixTimestamp()
        result.identifier shouldBeEqualTo givenPayload.deliveryID
        result.deliveryType.enumValue<DeliveryType>() shouldBeEqualTo givenData.type
        result.deliveryId shouldBeEqualTo givenPayload.deliveryID
        result.event.enumValue<MetricEvent>() shouldBeEqualTo givenPayload.event
        result.metadata shouldMatchTo givenPayload.metaData
    }

    @Test
    fun parse_givenDeliveryEventEmptyMetadata_expectTrackDeliveryEvent() {
        val givenDateStub = DateStub()
        val givenPayload = DeliveryPayload(
            deliveryID = String.random,
            event = MetricEvent.clicked,
            timestamp = givenDateStub.date,
            metaData = emptyMap()
        )
        val givenData = DeliveryEvent(
            type = DeliveryType.in_app,
            payload = givenPayload
        )
        val givenJson = encode(
            type = "TrackDeliveryEvent",
            data = givenData
        )

        val result = decode<MigrationTask.TrackDeliveryEvent>(givenJson)

        result.timestamp shouldBeEqualTo givenPayload.timestamp.getUnixTimestamp()
        result.identifier shouldBeEqualTo givenPayload.deliveryID
        result.deliveryType.enumValue<DeliveryType>() shouldBeEqualTo givenData.type
        result.deliveryId shouldBeEqualTo givenPayload.deliveryID
        result.event.enumValue<MetricEvent>() shouldBeEqualTo givenPayload.event
        result.metadata.shouldBeEmpty()
    }

    @Test
    fun parse_givenRegisterPushNotificationQueueTaskData_expectRegisterDeviceToken() {
        val givenData = RegisterPushNotificationQueueTaskData(
            profileIdentified = "profileIdentified",
            device = Device(
                token = String.random,
                platform = "android",
                lastUsed = DateStub().date,
                attributes = sampleAttributes
            )
        )
        val givenJson = encode(
            type = "RegisterDeviceToken",
            data = givenData
        )

        val result = decode<MigrationTask.RegisterDeviceToken>(givenJson)

        result.identifier shouldBeEqualTo givenData.profileIdentified
        result.token shouldBeEqualTo givenData.device.token
        result.platform shouldBeEqualTo givenData.device.platform
        result.lastUsed shouldBeEqualTo givenData.device.lastUsed.shouldNotBeNull().getUnixTimestamp()
        result.attributes shouldMatchTo givenData.device.attributes
    }

    @Test
    fun parse_givenRegisterPushNotificationQueueTaskDataIncorrectLastUsed_expectRegisterDeviceToken() {
        val givenDateStub = DateStub()
        val givenData = RegisterPushNotificationQueueTaskData(
            profileIdentified = "profileIdentified",
            device = Device(
                token = String.random,
                platform = "android",
                lastUsed = null,
                lastUsedDeprecated = givenDateStub.date,
                attributes = sampleAttributes
            )
        )
        val givenJson = encode(
            type = "RegisterDeviceToken",
            data = givenData
        )

        val deviceJson = givenJson.findJSONObjectAtPath("data.device").shouldNotBeNull()
        deviceJson.has("last_used") shouldBeEqualTo false
        deviceJson.getLong("lastUsed").shouldNotBeNull() shouldBeEqualTo givenDateStub.timestamp

        val result = decode<MigrationTask.RegisterDeviceToken>(givenJson)

        result.identifier shouldBeEqualTo givenData.profileIdentified
        result.token shouldBeEqualTo givenData.device.token
        result.platform shouldBeEqualTo givenData.device.platform
        result.lastUsed shouldBeEqualTo givenDateStub.timestamp
        result.attributes shouldMatchTo givenData.device.attributes
    }

    @Test
    fun parse_givenRegisterPushNotificationQueueTaskDataEmptyLastUsed_expectRegisterDeviceToken() {
        val givenDateStub = DateStub()
        val givenData = RegisterPushNotificationQueueTaskData(
            profileIdentified = "profileIdentified",
            device = Device(
                token = String.random,
                platform = "android",
                lastUsed = null,
                attributes = sampleAttributes
            )
        )
        val givenJson = encode(
            type = "RegisterDeviceToken",
            data = givenData
        )

        val deviceJson = givenJson.findJSONObjectAtPath("data.device").shouldNotBeNull()
        deviceJson.has("last_used") shouldBeEqualTo false
        deviceJson.has("lastUsed") shouldBeEqualTo false

        val result = decode<MigrationTask.RegisterDeviceToken>(
            jsonObject = givenJson,
            dateStub = givenDateStub
        )

        result.identifier shouldBeEqualTo givenData.profileIdentified
        result.token shouldBeEqualTo givenData.device.token
        result.platform shouldBeEqualTo givenData.device.platform
        result.lastUsed shouldBeEqualTo givenDateStub.timestamp
        result.attributes shouldMatchTo givenData.device.attributes
    }

    @Test
    fun parse_givenDeletePushNotificationQueueTaskData_expectDeletePushToken() {
        val givenDateStub = DateStub()
        val givenData = DeletePushNotificationQueueTaskData(
            profileIdentified = "profileIdentified",
            deviceToken = String.random
        )
        val givenJson = encode(
            type = "DeletePushToken",
            data = givenData
        )

        val result = decode<MigrationTask.DeletePushToken>(
            jsonObject = givenJson,
            dateStub = givenDateStub
        )

        result.timestamp shouldBeEqualTo givenDateStub.timestamp
        result.identifier shouldBeEqualTo givenData.profileIdentified
        result.token shouldBeEqualTo givenData.deviceToken
    }
}

/**
 * Encodes given data to JSONObject using same classes as previous implementation
 * in tracking module.
 */
private inline fun <reified T : Any> JsonAdapterTests.encode(
    storageId: String = UUID.randomUUID().toString(),
    type: String,
    data: T,
    totalRuns: Int = 0
): JSONObject {
    val queueTask = QueueTask(
        storageId = storageId,
        type = type,
        data = moshi.adapter(T::class.java).toJson(data),
        runResults = QueueTaskRunResults(totalRuns)
    )
    val result = moshi.adapter(QueueTask::class.java).toJson(queueTask)
    return JSONObject(result)
}

/**
 * Decodes given JSONObject to MigrationTask using implementation from
 * tracking-migration module.
 */
private inline fun <reified T : MigrationTask> JsonAdapterTests.decode(
    jsonObject: JSONObject,
    dateStub: DateStub? = null
): T {
    dateStub?.mock()
    val result = jsonAdapter.parseMigrationTask(jsonObject).fold(
        onSuccess = { result -> result.shouldNotBeNull().shouldBeInstanceOf<T>() },
        onFailure = { ex -> throw ex }
    )
    dateStub?.unmock()
    return result
}
