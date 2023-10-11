package io.customer.datapipeline

import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Properties
import com.segment.analytics.kotlin.core.Traits
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.JsonAnySerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

class ModuleDataPipeline(
    val config: DataPipelineModuleConfig
) {

    private var analytics: Analytics? = null

    fun initialize() {
        analytics = Analytics(writeKey = config.writeKey, context = config.application) {
            this.errorHandler = {
                it.printStackTrace()
//                    it.localizedMessage?.let { it1 -> diGraph.logger.error(it1) }
            }
        }
    }


    // Analytic event specific APIs

    /**
     * The track method is how you record any actions your users perform. Each action is known by a
     * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
     * For example a 'Purchased a Shirt' event might have properties like revenue or size.
     *
     * @param name Name of the action
     * @param properties [Properties] to describe the action.
     * @see <a href="https://segment.com/docs/spec/track/">Track Documentation</a>
     */
    @JvmOverloads
    fun track(name: String, properties: JsonObject = emptyJsonObject) {
        analytics?.track(name, properties)
    }

    /**
     * The track method is how you record any actions your users perform. Each action is known by a
     * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
     * For example a 'Purchased a Shirt' event might have properties like revenue or size.
     *
     * @param name Name of the action
     * @param properties to describe the action. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @param serializationStrategy strategy to serialize [properties]
     * @see <a href="https://segment.com/docs/spec/track/">Track Documentation</a>
     */
    fun <T> track(
        name: String, properties: T, serializationStrategy: SerializationStrategy<T>
    ) {
        analytics?.track(name, properties, serializationStrategy)
    }

    /**
     * The track method is how you record any actions your users perform. Each action is known by a
     * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
     * For example a 'Purchased a Shirt' event might have properties like revenue or size.
     *
     * @param name Name of the action
     * @param properties to describe the action. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @see <a href="https://segment.com/docs/spec/track/">Track Documentation</a>
     */
    inline fun <reified T> track(
        name: String, properties: T
    ) {
        track(name, properties, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * Identify lets you tie one of your users and their actions to a recognizable {@code userId}.
     * It also lets you record {@code traits} about the user, like their email, name, account type,
     * etc.
     *
     * <p>Traits and userId will be automatically cached and available on future sessions for the
     * same user. To update a trait on the server, call identify with the same user id.
     * You can also use {@link #identify(Traits)} for this purpose.
     *
     * In the case when user logs out, make sure to call {@link #reset()} to clear user's identity
     * info.
     *
     * @param userId Unique identifier which you recognize a user by in your own database
     * @param traits [Traits] about the user.
     * @see <a href="https://segment.com/docs/spec/identify/">Identify Documentation</a>
     */
    @JvmOverloads
    fun identify(userId: String, traits: JsonObject = emptyJsonObject) {
        analytics?.identify(userId, traits)
    }

    /**
     * Identify lets you tie one of your users and their actions to a recognizable {@code userId}.
     * It also lets you record {@code traits} about the user, like their email, name, account type,
     * etc.
     *
     * <p>Traits and userId will be automatically cached and available on future sessions for the
     * same user. To update a trait on the server, call identify with the same user id.
     * You can also use {@link #identify(Traits)} for this purpose.
     *
     * In the case when user logs out, make sure to call {@link #reset()} to clear user's identity
     * info.
     *
     * @param userId Unique identifier which you recognize a user by in your own database
     * @param traits [Traits] about the user. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @param serializationStrategy strategy to serialize [traits]
     * @see <a href="https://segment.com/docs/spec/identify/">Identify Documentation</a>
     */
    fun <T> identify(
        userId: String, traits: T, serializationStrategy: SerializationStrategy<T>
    ) {
        analytics?.identify(userId, traits, serializationStrategy)
    }

    /**
     * Identify lets you tie one of your users and their actions to a recognizable {@code userId}.
     * It also lets you record {@code traits} about the user, like their email, name, account type,
     * etc.
     *
     * <p>Traits and userId will be automatically cached and available on future sessions for the
     * same user. To update a trait on the server, call identify with the same user id.
     * You can also use {@link #identify(Traits)} for this purpose.
     *
     * In the case when user logs out, make sure to call {@link #reset()} to clear user's identity
     * info.
     *
     * @param traits [Traits] about the user. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @see <a href="https://segment.com/docs/spec/identify/">Identify Documentation</a>
     */
    inline fun <reified T> identify(
        traits: T
    ) {
        identify(traits, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * Identify lets you record {@code traits} about the user, like their email, name, account type,
     * etc.
     *
     * <p>Traits and userId will be automatically cached and available on future sessions for the
     * same user. To update a trait on the server, call identify with the same user id.
     * You can also use {@link #identify(Traits)} for this purpose.
     *
     * In the case when user logs out, make sure to call {@link #reset()} to clear user's identity
     * info.
     *
     * @param traits [Traits] about the user.
     * @see <a href="https://segment.com/docs/spec/identify/">Identify Documentation</a>
     */
    @JvmOverloads
    fun identify(traits: JsonObject = emptyJsonObject) {
        analytics?.identify(traits)
    }

    /**
     * Identify lets you tie one of your users and their actions to a recognizable {@code userId}.
     * It also lets you record {@code traits} about the user, like their email, name, account type,
     * etc.
     *
     * <p>Traits and userId will be automatically cached and available on future sessions for the
     * same user. To update a trait on the server, call identify with the same user id.
     * You can also use {@link #identify(Traits)} for this purpose.
     *
     * In the case when user logs out, make sure to call {@link #reset()} to clear user's identity
     * info.
     *
     * @param traits [Traits] about the user. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @param serializationStrategy strategy to serialize [traits]
     * @see <a href="https://segment.com/docs/spec/identify/">Identify Documentation</a>
     */
    fun <T> identify(
        traits: T, serializationStrategy: SerializationStrategy<T>
    ) {
        identify(Json.encodeToJsonElement(serializationStrategy, traits).jsonObject)
    }

    /**
     * Identify lets you tie one of your users and their actions to a recognizable {@code userId}.
     * It also lets you record {@code traits} about the user, like their email, name, account type,
     * etc.
     *
     * <p>Traits and userId will be automatically cached and available on future sessions for the
     * same user. To update a trait on the server, call identify with the same user id.
     * You can also use {@link #identify(Traits)} for this purpose.
     *
     * In the case when user logs out, make sure to call {@link #reset()} to clear user's identity
     * info.
     *
     * @param userId Unique identifier which you recognize a user by in your own database
     * @param traits [Traits] about the user. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @see <a href="https://segment.com/docs/spec/identify/">Identify Documentation</a>
     */
    inline fun <reified T> identify(
        userId: String, traits: T
    ) {
        identify(userId, traits, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * The screen methods let your record whenever a user sees a screen of your mobile app, and
     * attach a name, category or properties to the screen. Either category or name must be
     * provided.
     *
     * @param title A name for the screen.
     * @param category A category to describe the screen.
     * @param properties [Properties] to add extra information to this call.
     * @see <a href="https://segment.com/docs/spec/screen/">Screen Documentation</a>
     */
    @JvmOverloads
    fun screen(
        title: String, properties: JsonObject = emptyJsonObject, category: String = ""
    ) {
        analytics?.screen(title, properties, category)
    }

    /**
     * The screen methods let your record whenever a user sees a screen of your mobile app, and
     * attach a name, category or properties to the screen. Either category or name must be
     * provided.
     *
     * @param title A name for the screen.
     * @param category A category to describe the screen.
     * @param properties [Properties] to add extra information to this call. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @param serializationStrategy strategy to serialize [properties]
     * @see <a href="https://segment.com/docs/spec/screen/">Screen Documentation</a>
     */
    fun <T> screen(
        title: String,
        properties: T,
        serializationStrategy: SerializationStrategy<T>,
        category: String = ""
    ) {
        screen(
            title, Json.encodeToJsonElement(serializationStrategy, properties).jsonObject, category
        )
    }

    /**
     * The screen methods let your record whenever a user sees a screen of your mobile app, and
     * attach a name, category or properties to the screen. Either category or name must be
     * provided.
     *
     * @param title A name for the screen.
     * @param category A category to describe the screen.
     * @param properties [Properties] to add extra information to this call. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @see <a href="https://segment.com/docs/spec/screen/">Screen Documentation</a>
     */
    inline fun <reified T> screen(
        title: String, properties: T, category: String = ""
    ) {
        screen(title, properties, JsonAnySerializer.serializersModule.serializer(), category)
    }

    /**
     * The group method lets you associate a user with a group. It also lets you record custom
     * traits about the group, like industry or number of employees.
     *
     * <p>If you've called {@link #identify(String, Traits, Options)} before, this will
     * automatically remember the userId. If not, it will fall back to use the anonymousId instead.
     *
     * @param groupId Unique identifier which you recognize a group by in your own database
     * @param traits [Traits] about the group
     * @see <a href="https://segment.com/docs/spec/group/">Group Documentation</a>
     */
    @JvmOverloads
    fun group(groupId: String, traits: JsonObject = emptyJsonObject) {
        analytics?.group(groupId, traits)
    }

    /**
     * The group method lets you associate a user with a group. It also lets you record custom
     * traits about the group, like industry or number of employees.
     *
     * <p>If you've called {@link #identify(String, Traits, Options)} before, this will
     * automatically remember the userId. If not, it will fall back to use the anonymousId instead.
     *
     * @param groupId Unique identifier which you recognize a group by in your own database
     * @param traits [Traits] about the group. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @param serializationStrategy strategy to serialize [traits]
     * @see <a href="https://segment.com/docs/spec/group/">Group Documentation</a>
     */
    fun <T> group(
        groupId: String, traits: T, serializationStrategy: SerializationStrategy<T>
    ) {
        group(groupId, Json.encodeToJsonElement(serializationStrategy, traits).jsonObject)
    }

    /**
     * The group method lets you associate a user with a group. It also lets you record custom
     * traits about the group, like industry or number of employees.
     *
     * <p>If you've called {@link #identify(String, Traits, Options)} before, this will
     * automatically remember the userId. If not, it will fall back to use the anonymousId instead.
     *
     * @param groupId Unique identifier which you recognize a group by in your own database
     * @param traits [Traits] about the group. Needs to be [serializable](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md)
     * @see <a href="https://segment.com/docs/spec/group/">Group Documentation</a>
     */
    inline fun <reified T> group(
        groupId: String, traits: T
    ) {
        group(groupId, traits, JsonAnySerializer.serializersModule.serializer())
    }

    /**
     * The alias method is used to merge two user identities, effectively connecting two sets of
     * user data as one. This is an advanced method, but it is required to manage user identities
     * successfully in some of our integrations.
     *
     * @param newId The new ID you want to alias the existing ID to. The existing ID will be either
     *     the previousId if you have called identify, or the anonymous ID.
     * @see <a href="https://segment.com/docs/tracking-api/alias/">Alias Documentation</a>
     */
    fun alias(newId: String) {
        analytics?.alias(newId)
    }

    fun reset() = analytics?.reset()

}
