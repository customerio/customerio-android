package io.customer.sdk

import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.JsonAnySerializer
import io.customer.datapipelines.extensions.sanitizeForJson
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

    private inline fun <T> synchronized(block: () -> T): T = synchronized(this) { block() }

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
        identify(userId = userId, traits = traits, serializationStrategy = JsonAnySerializer.serializersModule.serializer())
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
        identify(userId = userId, traits = traits, serializationStrategy = JsonAnySerializer.serializersModule.serializer())
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
    fun identify(userId: String, traits: Map<String, Any?>) {
        // Method needed for Java interop as inline doesn't work with Java
        identify(userId = userId, traits = traits.sanitizeForJson(), serializationStrategy = JsonAnySerializer.serializersModule.serializer())
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
    fun <Traits> identify(
        userId: String,
        traits: Traits,
        serializationStrategy: SerializationStrategy<Traits>
    ) {
        synchronized {
            identifyImpl(userId, traits, serializationStrategy)
        }
    }

    /**
     * Update subscription topic preferences for the currently identified profile using Track Identify.
     * This replaces the entire topics map on the server side.
     * If no profile is identified, the call is ignored.
     */
    fun updateSubscriptionTopics(topics: Map<String, Boolean>) {
        val identifier = this.userId
        if (identifier.isNullOrBlank()) {
            // No identified user; nothing to update
            return
        }

        // Start from existing preferences (if present) to avoid clobbering other nested keys
        val existingPrefs = (profileAttributes["cio_subscription_preferences"] as? Map<*, *>)
            ?.entries
            ?.associate { (k, v) -> (k as? String ?: k.toString()) to v }
            ?.toMutableMap()
            ?: mutableMapOf()

        // Replace topics with provided map
        existingPrefs["topics"] = topics

        // Build traits payload: { "cio_subscription_preferences": { ...existing..., "topics": { ... } } }
        val traits: Map<String, Any?> = mapOf(
            "cio_subscription_preferences" to existingPrefs
        )

        identify(userId = identifier, traits = traits)
    }

    /**
     * Fetch subscription topic preferences from locally stored profile traits.
     * Returns an empty map if the value is not present or cannot be parsed.
     */
    fun getSubscriptionTopics(): Map<String, Boolean> {
        val attributes = profileAttributes
        val prefs = attributes["cio_subscription_preferences"] as? Map<*, *> ?: return emptyMap()
        val rawTopics = prefs["topics"] as? Map<*, *> ?: return emptyMap()

        val result = mutableMapOf<String, Boolean>()
        for ((key, value) in rawTopics) {
            val name = key as? String ?: continue
            val enabled = when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                else -> continue
            }
            result[name] = enabled
        }
        return result
    }

    /**
     * Implementation of identify to be overridden by subclasses.
     */
    protected abstract fun <Traits> identifyImpl(
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
        track(name = name, properties = properties, serializationStrategy = JsonAnySerializer.serializersModule.serializer())
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
    fun track(name: String, properties: Map<String, Any?>) {
        // Method needed for Java interop as inline doesn't work with Java
        track(name = name, properties = properties.sanitizeForJson(), serializationStrategy = JsonAnySerializer.serializersModule.serializer())
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
    fun <T> track(
        name: String,
        properties: T,
        serializationStrategy: SerializationStrategy<T>
    ) {
        synchronized {
            trackImpl(name, properties, serializationStrategy)
        }
    }

    /**
     * Implementation of track to be overridden by subclasses.
     */
    protected abstract fun <T> trackImpl(
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
        track(name = name, properties = properties, serializationStrategy = JsonAnySerializer.serializersModule.serializer())
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
        screen(title = title, properties = properties, serializationStrategy = JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * The screen methods represents screen views in your mobile apps
     *
     * @param title A name for the screen.
     * @param properties Additional details about the screen in Map <String, Any> format.
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/screen-spec/)
     */
    fun screen(title: String, properties: Map<String, Any?>) {
        // Method needed for Java interop as inline doesn't work with Java
        screen(title = title, properties = properties.sanitizeForJson(), serializationStrategy = JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * The screen methods represents screen views in your mobile apps
     *
     * @param title A name for the screen.
     * @param properties Additional details about the screen.
     * @see [Learn more](https://customer.io/docs/cdp/sources/source-spec/screen-spec/)
     */
    fun <T> screen(
        title: String,
        properties: T,
        serializationStrategy: SerializationStrategy<T>
    ) {
        synchronized {
            screenImpl(title, properties, serializationStrategy)
        }
    }

    /**
     * Implementation of screen to be overridden by subclasses.
     */
    protected abstract fun <T> screenImpl(
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
        screen(title = title, properties = properties, serializationStrategy = JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * Stop identifying the currently persisted customer. All future calls to the SDK will no longer
     * be associated with the previously identified customer.
     * Note: If you simply want to identify a *new* customer, this function call is optional. Simply
     * call `identify()` again to identify the new customer profile over the existing.
     * If no profile has been identified yet, this function will reset anonymous profile.
     */
    fun clearIdentify() {
        synchronized {
            clearIdentifyImpl()
        }
    }

    /**
     * Implementation of clearIdentify to be overridden by subclasses.
     */
    protected abstract fun clearIdentifyImpl()

    /**
     * The track method helps manually record metric events for push notifications and
     * in-app messages.
     *
     * @param event [TrackMetric] event to be tracked.
     */
    fun trackMetric(event: TrackMetric) {
        synchronized {
            trackMetricImpl(event)
        }
    }

    /**
     * Implementation of trackMetric to be overridden by subclasses.
     */
    protected abstract fun trackMetricImpl(event: TrackMetric)

    /**
     * The device token that is currently registered with the push notification service.
     */
    abstract val registeredDeviceToken: String?

    /**
     * The anonymousId that is currently associated with the user.
     */
    abstract val anonymousId: String

    /**
     * The userId that is currently associated with the user.
     */
    abstract val userId: String?

    /**
     * Use to provide additional and custom device attributes
     * apart from the ones the SDK is programmed to send to customer workspace.
     */
    abstract var deviceAttributes: CustomAttributes

    /**
     * Registers a new device token with Customer.io, associated with the current
     * profile. If there is no profile identified yet, this will store the device
     * token and associate it with anonymous profile, and later merge it to
     * identified profile.
     */
    fun registerDeviceToken(deviceToken: String) {
        synchronized {
            registerDeviceTokenImpl(deviceToken)
        }
    }

    /**
     * Implementation of registerDeviceToken to be overridden by subclasses.
     */
    protected abstract fun registerDeviceTokenImpl(deviceToken: String)

    /**
     * Delete the currently registered device token
     */
    fun deleteDeviceToken() {
        synchronized {
            deleteDeviceTokenImpl()
        }
    }

    /**
     * Implementation of deleteDeviceToken to be overridden by subclasses.
     */
    protected abstract fun deleteDeviceTokenImpl()
}
