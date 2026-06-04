package io.customer.messagingpush.logger

import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.sdk.core.util.Logger

internal class PushNotificationLogger(private val logger: Logger) {

    companion object {
        const val TAG = "Push"
        const val HANDOFF_TAG = "CIO-Push-Handoff"
    }

    fun logPendingStoreAppended(deliveryId: String) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "appended pending entry key=$deliveryId"
        )
    }

    fun logWorkManagerEnqueued(deliveryId: String) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "enqueued WM key=$deliveryId"
        )
    }

    fun logWorkManagerUnavailableAsyncFallback(deliveryId: String) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "WM unavailable, async-tracker fallback key=$deliveryId"
        )
    }

    fun logWorkerSuccessRemoved(deliveryId: String) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "worker success, removed key=$deliveryId"
        )
    }

    fun logWorkerRetry(deliveryId: String, cause: Throwable?) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "worker retry key=$deliveryId cause=${cause?.javaClass?.simpleName ?: "unknown"}"
        )
    }

    fun logWorkerFailure(deliveryId: String, cause: Throwable?) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "worker failure key=$deliveryId cause=${cause?.javaClass?.simpleName ?: "unknown"}"
        )
    }

    fun logForegroundSnapshot(count: Int) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "foreground reached, snapshot count=$count"
        )
    }

    fun logHandoffCancelledWorkManager(deliveryId: String) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "cancelled WM key=$deliveryId"
        )
    }

    fun logHandoffPublishedToEventBus(deliveryId: String) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "published to eventBus key=$deliveryId"
        )
    }

    fun logHandoffComplete(count: Int) {
        logger.info(
            tag = HANDOFF_TAG,
            message = "handoff complete, removed count=$count"
        )
    }

    fun logHandoffEntryFailed(deliveryId: String, throwable: Throwable) {
        logger.error(
            tag = HANDOFF_TAG,
            message = "handoff entry failed key=$deliveryId cause=${throwable.javaClass.simpleName}",
            throwable = throwable
        )
    }

    fun logGooglePlayServicesAvailable() {
        logger.debug(
            tag = TAG,
            message = "Google Play Services is available for this device"
        )
    }

    fun logGooglePlayServicesUnavailable(result: Int) {
        logger.debug(
            tag = TAG,
            message = "Google Play Services is NOT available for this device with result: $result"
        )
    }

    fun logGooglePlayServicesAvailabilityCheckFailed(throwable: Throwable) {
        logger.error(
            tag = TAG,
            message = "Checking Google Play Service availability check failed with error: : ${throwable.message}",
            throwable = throwable
        )
    }

    fun obtainingTokenStarted() {
        logger.debug(
            tag = TAG,
            message = "Getting current device token from Firebase messaging on app launch"
        )
    }

    fun obtainingTokenSuccess(token: String) {
        logger.debug(
            tag = TAG,
            message = "Got current device token: $token"
        )
    }

    fun obtainingTokenFailed(throwable: Throwable?) {
        logger.error(
            tag = TAG,
            message = "Failed to get device token with error: ${throwable?.message}",
            throwable = throwable
        )
    }

    fun logShowingPushNotification(message: RemoteMessage) {
        logger.debug(
            tag = TAG,
            message = "Showing notification for message: ${toString(message)}"
        )
    }

    fun logReceivedPushMessage(message: RemoteMessage, handleNotificationTrigger: Boolean) {
        logger.debug(
            tag = TAG,
            message = "handleNotificationTrigger: $handleNotificationTrigger - Received notification for message: ${toString(message)}"
        )
    }

    fun logReceivedEmptyPushMessage() {
        logger.debug(
            tag = TAG,
            message = "Push message received is empty"
        )
    }

    fun logReceivedCioPushMessage() {
        logger.debug(
            tag = TAG,
            message = "Received CIO push message"
        )
    }

    fun logReceivedNonCioPushMessage() {
        logger.debug(
            tag = TAG,
            message = "Received non CIO push message, ignoring message"
        )
    }

    fun logReceivedPushMessageWithEmptyDeliveryId() {
        logger.debug(
            tag = TAG,
            message = "Received message with empty deliveryId"
        )
    }

    fun logReceivedDuplicatePushMessageDeliveryId(deliveryId: String) {
        logger.debug(
            tag = TAG,
            message = "Received duplicate message with deliveryId: $deliveryId"
        )
    }

    fun logReceivedNewMessageWithDeliveryId(deliveryId: String) {
        logger.debug(
            tag = TAG,
            message = "Received new message with deliveryId: $deliveryId"
        )
    }

    fun logPushMetricsAutoTrackingDisabled() {
        logger.debug(
            tag = TAG,
            message = "Received message but auto tracking is disabled"
        )
    }

    fun logTrackingPushMessageDelivered(deliveryId: String) {
        logger.debug(
            tag = TAG,
            message = "Tracking push message delivered with deliveryId: $deliveryId"
        )
    }

    fun logTrackingPushMessageOpened(payload: CustomerIOParsedPushPayload) {
        logger.debug(
            tag = TAG,
            message = "Tracking push message opened with payload: $payload"
        )
    }

    fun logFailedToHandlePushClick(throwable: Throwable) {
        logger.error(
            tag = TAG,
            message = "Failed to handle push click: ${throwable.message}",
            throwable = throwable
        )
    }

    fun logHandlingNotificationDeepLink(payload: CustomerIOParsedPushPayload, behavior: PushClickBehavior) {
        logger.debug(
            tag = TAG,
            message = "Handling push notification deep link with payload: $payload - pushClickBehavior: $behavior"
        )
    }

    fun logDeepLinkHandledByCallback() {
        logger.debug(
            tag = TAG,
            message = "Deep link handled by host app callback implementation"
        )
    }

    fun logDeepLinkHandledByHostApp() {
        logger.debug(
            tag = TAG,
            message = "Deep link handled by internal host app navigation"
        )
    }

    fun logDeepLinkHandledExternally() {
        logger.debug(
            tag = TAG,
            message = "Deep link handled by external app"
        )
    }

    fun logDeepLinkHandledDefaultHostAppLauncher() {
        logger.debug(
            tag = TAG,
            message = "Deep link handled by opening default host app"
        )
    }

    fun logDeepLinkWasNotHandled() {
        logger.debug(
            tag = TAG,
            message = "Deep link was not handled"
        )
    }

    fun logNotificationActivityStartedWithInvalidIntent() {
        logger.error(
            tag = TAG,
            message = "Intent is null, cannot process notification click"
        )
    }

    fun logCreatingNotificationChannel(channelId: String, channelName: String, importance: Int) {
        logger.debug(
            tag = TAG,
            message = "Creating new notification channel id: $channelId, name: $channelName, importance: $importance"
        )
    }

    fun logNotificationChannelAlreadyExists(channelId: String) {
        logger.debug(
            tag = TAG,
            message = "Notification channel already exists id: $channelId"
        )
    }

    fun logInvalidNotificationChannelImportance(importanceLevel: Int) {
        logger.error(
            tag = TAG,
            message = "Notification channel importance level invalid: $importanceLevel"
        )
    }

    private fun toString(message: RemoteMessage): String {
        val notification = message.notification ?: return message.data.toString()
        return buildString {
            appendLine("Notification:")
            appendLine("  title = ${notification.title}")
            appendLine("  body = ${notification.body}")
            appendLine("  icon = ${notification.icon}")
            appendLine("  color = ${notification.color}")
            appendLine("  imageUrl = ${notification.imageUrl}")
            appendLine("Data: ${message.data}")
        }
    }
}
