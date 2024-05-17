package io.customer.sdk

import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.JsonAnySerializer
import io.customer.sdk.android.CustomerIOInstance
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.events.TrackMetric
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Extends [CustomerIOInstance] to provide the instance of CustomerIO SDK with
 * Data Pipelines implementation.
 */
abstract class DataPipelineInstance : CustomerIOInstance {
    /**
     * Custom profile attributes to be added to current profile.
     */
    abstract var profileAttributes: CustomAttributes

    /**
     * Identify a customer (aka: Add or update a profile).
     * [Learn more](https://customer.io/docs/identifying-people/) about identifying a customer in Customer.io
     * Note: You can only identify 1 profile at a time in your SDK. If you call this function multiple times,
     * the previously identified profile will be removed. Only the latest identified customer is persisted.
     *
     * @param Traits Serializable json object to be added.
     * @param userId Identifier you want to assign to the customer.
     * This value can be an internal ID that your system uses or an email address.
     * [Learn more](https://customer.io/docs/api/#operation/identify)
     * @param traits [Traits] about the user. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     */
    inline fun <reified Traits> identify(userId: String, traits: Traits) {
        identify(userId, traits, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * Identify a customer (aka: Add or update a profile).
     * [Learn more](https://customer.io/docs/identifying-people/) about identifying a customer in Customer.io
     * Note: You can only identify 1 profile at a time in your SDK. If you call this function multiple times,
     * the previously identified profile will be removed. Only the latest identified customer is persisted.
     *
     * @param userId Identifier you want to assign to the customer.
     * This value can be an internal ID that your system uses or an email address.
     * [Learn more](https://customer.io/docs/api/#operation/identify)
     * @param traits JsonObject about the user.
     */
    @JvmOverloads
    fun identify(userId: String, traits: JsonObject = emptyJsonObject) {
        identify(userId, traits, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * Identify a customer (aka: Add or update a profile).
     * [Learn more](https://customer.io/docs/identifying-people/) about identifying a customer in Customer.io
     * Note: You can only identify 1 profile at a time in your SDK. If you call this function multiple times,
     * the previously identified profile will be removed. Only the latest identified customer is persisted.
     *
     * @param userId Identifier you want to assign to the customer.
     * This value can be an internal ID that your system uses or an email address.
     * [Learn more](https://customer.io/docs/api/#operation/identify)
     * @param traits Map of <String, Any> to be added
     */
    fun identify(userId: String, traits: CustomAttributes) {
        // Method needed for Java interop as inline doesn't work with Java
        identify(userId, traits, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * Identify a customer (aka: Add or update a profile).
     * [Learn more](https://customer.io/docs/identifying-people/) about identifying a customer in Customer.io
     * Note: You can only identify 1 profile at a time in your SDK. If you call this function multiple times,
     * the previously identified profile will be removed. Only the latest identified customer is persisted.
     *
     * @param Traits Serializable json object to be added.
     * @param userId Identifier you want to assign to the customer.
     * This value can be an internal ID that your system uses or an email address.
     * [Learn more](https://customer.io/docs/api/#operation/identify)
     * @param traits [Traits] about the user. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @param serializationStrategy strategy to serialize [traits]
     */
    abstract fun <Traits> identify(
        userId: String,
        traits: Traits,
        serializationStrategy: SerializationStrategy<Traits>
    )

    /**
     * The track method helps you record events: the things your users do on your app.
     * Each track call records a single event. Each event has a name and properties.
     * For example, if you send a track call when someone starts a video on your app,
     * the name of the event might be Video Started,
     * and the properties might include the title of the video, the length of the video, and so on.
     *
     * @param name Name of the action
     * @param properties custom values providing extra information about the event.
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/track-spec/)
     */
    @JvmOverloads
    fun track(name: String, properties: JsonObject = emptyJsonObject) {
        track(name, properties, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * The track method helps you record events: the things your users do on your app.
     * Each track call records a single event. Each event has a name and properties.
     * For example, if you send a track call when someone starts a video on your app,
     * the name of the event might be Video Started,
     * and the properties might include the title of the video, the length of the video, and so on.
     *
     * @param name Name of the action
     * @param properties Map of <String, Any> to be added
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/track-spec/)
     */
    fun track(name: String, properties: CustomAttributes) {
        // Method needed for Java interop as inline doesn't work with Java
        track(name, properties, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * The track method helps you record events: the things your users do on your app.
     * Each track call records a single event. Each event has a name and properties.
     * For example, if you send a track call when someone starts a video on your app,
     * the name of the event might be Video Started,
     * and the properties might include the title of the video, the length of the video, and so on.
     *
     * @param name Name of the action
     * @param properties to describe the action. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @param serializationStrategy strategy to serialize [properties]
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/track-spec/)
     */
    abstract fun <T> track(
        name: String,
        properties: T,
        serializationStrategy: SerializationStrategy<T>
    )

    /**
     * The track method helps you record events: the things your users do on your app.
     * Each track call records a single event. Each event has a name and properties.
     * For example, if you send a track call when someone starts a video on your app,
     * the name of the event might be Video Started,
     * and the properties might include the title of the video, the length of the video, and so on.
     *
     * @param name Name of the action
     * @param properties to describe the action. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/track-spec/)
     */
    inline fun <reified T> track(
        name: String,
        properties: T
    ) {
        track(name, properties, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * The screen methods represents screen views in your mobile apps
     *
     * @param title A name for the screen.
     * @param properties Additional details about the screen.
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/screen-spec/)
     */
    @JvmOverloads
    fun screen(title: String, properties: JsonObject = emptyJsonObject) {
        screen(title, properties, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * The screen methods represents screen views in your mobile apps
     *
     * @param title A name for the screen.
     * @param properties Additional details about the screen in Map <String, Any> format.
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/screen-spec/)
     */
    fun screen(title: String, properties: CustomAttributes) {
        // Method needed for Java interop as inline doesn't work with Java
        screen(title, properties, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * The screen methods represents screen views in your mobile apps
     *
     * @param title A name for the screen.
     * @param properties Additional details about the screen.
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/screen-spec/)
     */
    abstract fun <T> screen(
        title: String,
        properties: T,
        serializationStrategy: SerializationStrategy<T>
    )

    /**
     * The screen methods represents screen views in your mobile apps
     *
     * @param title A name for the screen.
     * @param properties Additional details about the screen.
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/screen-spec/)
     */
    inline fun <reified T> screen(
        title: String,
        properties: T
    ) {
        screen(title, properties, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * Stop identifying the currently persisted customer. All future calls to the SDK will no longer
     * be associated with the previously identified customer.
     * Note: If you simply want to identify a *new* customer, this function call is optional. Simply
     * call `identify()` again to identify the new customer profile over the existing.
     * If no profile has been identified yet, this function will reset anonymous profile.
     */
    abstract fun clearIdentify()

    /**
     * The track method helps manually record metric events for push notifications and
     * in-app messages.
     *
     * @param event [TrackMetric] event to be tracked.
     */
    abstract fun trackMetric(event: TrackMetric)
}
