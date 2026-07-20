package io.customer.messagingpush.livenotification

import io.customer.messagingpush.di.liveNotificationManager
import io.customer.messagingpush.di.pushModuleConfig
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus

/**
 * Emits `register_push_to_start` track events for the enabled live-notification
 * types when the device token or user changes, deduping already-registered
 * `token|userId` signatures. Registers only for identified users.
 */
internal class LiveNotificationRegistrar(
    private val client: LiveNotificationLifecycleClient,
    private val store: LiveNotificationStore
) {

    @Volatile
    private var token: String? = null

    @Volatile
    private var userId: String = ""

    @Volatile
    private var isIdentified: Boolean = false

    private val enabledTypes: Set<String>
        get() = SDKComponent.pushModuleConfig.liveNotificationTypes

    fun start() {
        val clearedByMigration = store.migrate()
        if (clearedByMigration > 0) {
            SDKComponent.logger.debug(
                "Live Notifications: migration cleared $clearedByMigration stale registration(s) from the old namespace."
            )
        }
        store.trimStaleTimestamps()

        eventBus.subscribe(Event.RegisterDeviceTokenEvent::class) { onDeviceTokenChanged(it.token) }
        eventBus.subscribe(Event.UserChangedEvent::class) { onUserChanged(it) }
        eventBus.subscribe(Event.DeleteDeviceTokenEvent::class) { onDeviceTokenDeleted() }
        eventBus.subscribe(Event.ResetEvent::class) { onReset() }
    }

    internal fun onDeviceTokenChanged(newToken: String) {
        token = newToken
        registerAll()
    }

    internal fun onUserChanged(event: Event.UserChangedEvent) {
        userId = event.userId ?: event.anonymousId
        isIdentified = event.userId != null
        // Feed identity to the lifecycle client synchronously so local start/update/end
        // reporting isn't gated by the pipeline's laggy isUserIdentified flag.
        client.setIdentified(isIdentified)
        registerAll()
    }

    internal fun onDeviceTokenDeleted() {
        token = null
        // Drop dedup signatures so re-registering the same token later isn't skipped.
        store.clearRegistrations()
    }

    internal fun onReset() {
        SDKComponent.liveNotificationManager.cancelAllActivities()
        store.clearRegistrations()
        // Drop identity so post-logout local start/update/end aren't reported as the
        // previous (identified) user until a new UserChangedEvent arrives.
        userId = ""
        isIdentified = false
        client.setIdentified(false)
    }

    private fun registerAll() {
        if (!isIdentified) {
            if (token != null && enabledTypes.isNotEmpty()) {
                SDKComponent.logger.debug(
                    "Live Notifications: holding push-to-start registration for ${enabledTypes.size} type(s); no identified user."
                )
            }
            return
        }
        val currentToken = token ?: return
        val signature = "$currentToken|$userId"
        for (activityType in enabledTypes) {
            if (store.registrationSignature(activityType) == signature) continue
            if (client.registerPushToStart(activityType, currentToken)) {
                store.setRegistrationSignature(activityType, signature)
            }
        }
    }
}
