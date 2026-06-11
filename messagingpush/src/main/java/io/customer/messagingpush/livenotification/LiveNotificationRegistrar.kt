package io.customer.messagingpush.livenotification

import io.customer.messagingpush.di.pushModuleConfig
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus

/**
 * Registers the device's FCM token with the backend for the live-notification
 * activity types the host app enabled via
 * `MessagingPushModuleConfig.setLiveNotificationTypes` (the Android analogue of
 * iOS push-to-start registration). No types enabled ⇒ nothing is registered.
 *
 * Registration is (re)attempted whenever the device token rotates
 * ([Event.RegisterDeviceTokenEvent]) or the user changes
 * ([Event.UserChangedEvent]), and is deduped per activity type via
 * [LiveNotificationStore] (signature = `token|userId`). Token deletion / reset
 * clears the stored signatures so the next token re-registers.
 */
internal class LiveNotificationRegistrar(
    private val client: LiveNotificationLifecycleClient,
    private val store: LiveNotificationStore
) {

    @Volatile
    private var token: String? = null

    @Volatile
    private var userId: String = ""

    /** The current FCM token, or null if not yet received. */
    fun currentToken(): String? = token

    /** The current resolved user identity (identified userId, else anonymousId). */
    fun currentUserId(): String = userId

    private val enabledTypes: Set<String>
        get() = SDKComponent.pushModuleConfig.liveNotificationTypes

    fun start() {
        // Drop dedup entries for activities that ended long ago without an explicit `end`.
        store.trimStaleTimestamps()

        eventBus.subscribe(Event.RegisterDeviceTokenEvent::class) { event ->
            token = event.token
            registerAll()
        }
        eventBus.subscribe(Event.UserChangedEvent::class) { event ->
            userId = event.userId ?: event.anonymousId
            registerAll()
        }
        eventBus.subscribe(Event.DeleteDeviceTokenEvent::class) {
            token = null
            store.clearRegistrations()
        }
        eventBus.subscribe(Event.ResetEvent::class) {
            store.clearRegistrations()
        }
    }

    private suspend fun registerAll() {
        val currentToken = token ?: return
        val signature = "$currentToken|$userId"
        for (activityType in enabledTypes) {
            if (store.registrationSignature(activityType) == signature) continue
            val result = client.registerForActivityType(activityType, currentToken, userId)
            if (result.isSuccess) {
                store.setRegistrationSignature(activityType, signature)
            } else {
                SDKComponent.logger.debug(
                    "Live notification registration failed for '$activityType'; will retry on next token/user change."
                )
            }
        }
    }
}
