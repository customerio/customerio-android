@file:OptIn(InternalCustomerIOApi::class)

package io.customer.messagingpush.di

import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.AsyncPushDeliveryTracker
import io.customer.messagingpush.BackgroundPushCallbackFallback
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.PushDeliveryTracker
import io.customer.messagingpush.PushDeliveryTrackerImpl
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClient
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl
import io.customer.messagingpush.livenotification.LiveNotificationManager
import io.customer.messagingpush.livenotification.LiveNotificationRegistrar
import io.customer.messagingpush.livenotification.LiveNotificationStore
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.processor.BackgroundPushDeliveryMetrics
import io.customer.messagingpush.processor.PushDeliveryMetricsBackgroundScheduler
import io.customer.messagingpush.processor.PushMessageProcessor
import io.customer.messagingpush.processor.PushMessageProcessorImpl
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.provider.FCMTokenProviderImpl
import io.customer.messagingpush.provider.FirebaseTokenSource
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.store.PushSettingsStore
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.DeepLinkUtilImpl
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.messagingpush.util.PushTrackingUtilImpl
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.workManagerProvider
import io.customer.sdk.data.store.PendingDeliveryFlusher
import io.customer.sdk.data.store.PendingDeliveryStore

/*
This file contains a series of extensions to the common module's Dependency injection (DI) graph. All extensions in this file simply add internal classes for this module into the DI graph.

The use of extensions was chosen over creating a separate graph class for each module. This simplifies the SDK code as well as automated tests code dramatically.
 */

internal val AndroidSDKComponent.fcmTokenProvider: DeviceTokenProvider
    get() = newInstance<DeviceTokenProvider> {
        FCMTokenProviderImpl(
            context = applicationContext,
            googleApiAvailabilityProvider = { GoogleApiAvailability.getInstance() },
            tokenSource = FirebaseTokenSource(
                context = applicationContext,
                firebaseMessaging = { FirebaseMessaging.getInstance() },
                firebaseInstallations = { FirebaseInstallations.getInstance() },
                pushLogger = SDKComponent.pushLogger
            ),
            pushLogger = SDKComponent.pushLogger
        )
    }

val SDKComponent.pushModuleConfig: MessagingPushModuleConfig
    get() = newInstance {
        modules[ModuleMessagingPushFCM.MODULE_NAME]?.moduleConfig as? MessagingPushModuleConfig ?: MessagingPushModuleConfig.default()
    }

/** The registered push module's config, or null until the push module is registered, so silent push code can tell the defaults from the app's settings. */
internal val SDKComponent.registeredPushModuleConfig: MessagingPushModuleConfig?
    get() = modules[ModuleMessagingPushFCM.MODULE_NAME]?.moduleConfig as? MessagingPushModuleConfig

/** See [BackgroundPushCallbackFallback]. Only wrapper SDKs set it. */
@InternalCustomerIOApi
val SDKComponent.backgroundPushCallbackFallback: BackgroundPushCallbackFallback
    get() = singleton<BackgroundPushCallbackFallback> { BackgroundPushCallbackFallback() }

internal val SDKComponent.deepLinkUtil: DeepLinkUtil
    get() = newInstance<DeepLinkUtil> { DeepLinkUtilImpl(logger, pushModuleConfig) }

@InternalCustomerIOApi
val SDKComponent.pushTrackingUtil: PushTrackingUtil
    get() = newInstance<PushTrackingUtil> { PushTrackingUtilImpl() }

internal val SDKComponent.pendingPushDeliveryStore: PendingDeliveryStore<PendingPushDeliveryMetric>
    get() = singleton<PendingDeliveryStore<PendingPushDeliveryMetric>> {
        PendingDeliveryStore(
            context = android().applicationContext,
            fileName = PendingPushDeliveryMetric.FILE_NAME,
            elementSerializer = PendingPushDeliveryMetric.serializer(),
            logger = logger
        )
    }

internal val SDKComponent.heldPushDeliveryStore: PendingDeliveryStore<PendingPushDeliveryMetric>
    get() = singleton<PendingDeliveryStore<PendingPushDeliveryMetric>>(identifier = PendingPushDeliveryMetric.HELD_FILE_NAME) {
        PendingDeliveryStore(
            context = android().applicationContext,
            fileName = PendingPushDeliveryMetric.HELD_FILE_NAME,
            elementSerializer = PendingPushDeliveryMetric.serializer(),
            logger = logger,
            maxEntries = PendingPushDeliveryMetric.HELD_MAX_ENTRIES
        )
    }

internal val SDKComponent.pushSettingsStore: PushSettingsStore
    get() = singleton<PushSettingsStore> {
        val context = android().applicationContext
        PushSettingsStore(context) { PushSettingsStore.installedAppVersion(context) }
    }

internal val SDKComponent.pushDeliveryFlusher: PendingDeliveryFlusher<PendingPushDeliveryMetric>
    get() = singleton {
        PendingDeliveryFlusher(
            store = pendingPushDeliveryStore,
            workManagerProvider = workManagerProvider,
            dispatchersProvider = dispatchersProvider
        )
    }

internal val SDKComponent.asyncPushDeliveryTracker: AsyncPushDeliveryTracker
    get() = singleton<AsyncPushDeliveryTracker> {
        AsyncPushDeliveryTracker(
            deliveryTracker = pushDeliveryTracker,
            pendingStore = pendingPushDeliveryStore
        )
    }

internal val SDKComponent.deliveryMetricsScheduler: PushDeliveryMetricsBackgroundScheduler
    get() = singleton<PushDeliveryMetricsBackgroundScheduler> { PushDeliveryMetricsBackgroundScheduler(workManagerProvider, asyncPushDeliveryTracker) }

internal val SDKComponent.pushMessageProcessor: PushMessageProcessor
    get() = singleton<PushMessageProcessor> {
        PushMessageProcessorImpl(
            pushLogger = pushLogger,
            moduleConfig = pushModuleConfig,
            deepLinkUtil = deepLinkUtil,
            deliveryMetricsScheduler = deliveryMetricsScheduler,
            pendingPushDeliveryStore = pendingPushDeliveryStore
        )
    }

internal val SDKComponent.backgroundPushDeliveryMetrics: BackgroundPushDeliveryMetrics
    get() = singleton<BackgroundPushDeliveryMetrics> {
        BackgroundPushDeliveryMetrics(
            pushLogger = pushLogger,
            registeredModuleConfig = { registeredPushModuleConfig },
            pushSettingsStore = pushSettingsStore,
            heldStore = heldPushDeliveryStore,
            pendingStore = pendingPushDeliveryStore,
            deliveryMetricsScheduler = deliveryMetricsScheduler
        )
    }

internal val SDKComponent.liveNotificationStore: LiveNotificationStore
    get() = singleton<LiveNotificationStore> { LiveNotificationStore(android().applicationContext) }

internal val SDKComponent.liveNotificationLifecycleClient: LiveNotificationLifecycleClient
    get() = singleton<LiveNotificationLifecycleClient> { LiveNotificationLifecycleClientImpl() }

internal val SDKComponent.liveNotificationRegistrar: LiveNotificationRegistrar
    get() = singleton<LiveNotificationRegistrar> {
        LiveNotificationRegistrar(liveNotificationLifecycleClient, liveNotificationStore)
    }

internal val SDKComponent.liveNotificationManager: LiveNotificationManager
    get() = singleton<LiveNotificationManager> {
        LiveNotificationManager(
            lifecycleClient = liveNotificationLifecycleClient
        )
    }

internal val SDKComponent.pushDeliveryTracker: PushDeliveryTracker
    get() = singleton<PushDeliveryTracker> { PushDeliveryTrackerImpl() }

internal val SDKComponent.pushLogger: PushNotificationLogger
    get() = singleton<PushNotificationLogger> { PushNotificationLogger(logger) }
