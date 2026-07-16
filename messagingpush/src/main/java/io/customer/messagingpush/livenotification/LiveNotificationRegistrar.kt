package io.customer.messagingpush.livenotification

import io.customer.messagingpush.di.liveNotificationManager
import io.customer.messagingpush.di.pushModuleConfig
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus

/**
 * Emits `register_push_to_start` track events for the enabled live-notification
 * types so Customer.io can remotely start them.
 *
 * Registration is a CDP track event, so the data pipeline owns batching, retry
 * and delivery — this type does not retry itself. It only decides WHEN to emit:
 * whenever the device token ([Event.RegisterDeviceTokenEvent]) or user
 * ([Event.UserChangedEvent]) changes, for each enabled type whose
 * `token|userId` signature isn't already registered ([LiveNotificationStore]).
 *
 * The signature dedup means re-identifying the same user with the same token —
 * e.g. on every app launch — does NOT re-send the same registrations. A new
 * token or a new user yields a new signature and re-registers.
 *
 * Live notifications are auth-only. This registrar is the authoritative identity
 * gate for registration: it registers only while [isIdentified], which it derives
 * directly from [Event.UserChangedEvent] (`userId != null`). It deliberately does
 * NOT rely on the data pipeline's `isUserIdentified` flag here, because that flag
 * reads a cached user id that updates asynchronously *after* the identify — on the
 * login turn it can still read false when this subscriber runs, which previously
 * dropped the registration with no later trigger to re-fire it that launch. The
 * event carries the identity synchronously, so gating on it closes that race
 * without waiting/polling. (The track event's own user attribution is handled by
 * the analytics store, whose reducer has already applied the identify by the time
 * the registration is enqueued.)
 */
internal class LiveNotificationRegistrar(
    private val client: LiveNotificationLifecycleClient,
    private val store: LiveNotificationStore
) {

    @Volatile
    private var token: String? = null

    @Volatile
    private var userId: String = ""

    /** Whether the current profile is identified, per the latest [Event.UserChangedEvent]. */
    @Volatile
    private var isIdentified: Boolean = false

    private val enabledTypes: Set<String>
        get() = SDKComponent.pushModuleConfig.liveNotificationTypes

    fun start() {
        // One-time cleanup of registration signatures left over from the old built-in
        // namespace, so the new identifiers register cleanly on the next identify.
        val clearedByMigration = store.migrate()
        if (clearedByMigration > 0) {
            SDKComponent.logger.debug(
                "Live Notifications: migration cleared $clearedByMigration stale registration(s) from the old namespace."
            )
        }
        // Drop dedup entries for activities that ended long ago without an explicit `end`.
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
        // Authoritative identity signal — carried synchronously by the event, unlike the
        // pipeline's asynchronously-updated isUserIdentified flag.
        isIdentified = event.userId != null
        registerAll()
    }

    internal fun onDeviceTokenDeleted() {
        // No token ⇒ nothing to register; a new token re-registers via its own signature.
        // We deliberately do NOT clear stored signatures here: doing so made the routine
        // delete+re-register token cycle on identify re-send every registration on each launch.
        token = null
    }

    internal fun onReset() {
        // Logout: remove the user's live notifications (no `end` event is sent) so they
        // don't linger for the next user, and clear registrations so the next identified
        // user re-registers. ResetEvent only fires on explicit clearIdentify, so unlike the
        // routine token cycle above it's safe to clear signatures here.
        SDKComponent.liveNotificationManager.cancelAllActivities()
        store.clearRegistrations()
    }

    private fun registerAll() {
        // Register only for identified users. This is the single identity gate for registration;
        // the client no longer re-checks the (laggy) pipeline flag for the token event.
        if (!isIdentified) {
            // Held: a token captured while anonymous is not sent (matches the iOS registrar). It
            // re-fires automatically on the next identify (registerAll runs from onUserChanged).
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
