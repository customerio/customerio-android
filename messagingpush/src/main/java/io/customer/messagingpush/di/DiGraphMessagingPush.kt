@file:OptIn(InternalCustomerIOApi::class)

package io.customer.messagingpush.di

import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.PushDeliveryTracker
import io.customer.messagingpush.PushDeliveryTrackerImpl
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.network.HttpClient
import io.customer.messagingpush.network.HttpClientImpl
import io.customer.messagingpush.processor.PushMessageProcessor
import io.customer.messagingpush.processor.PushMessageProcessorImpl
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.provider.FCMTokenProviderImpl
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.DeepLinkUtilImpl
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.messagingpush.util.PushTrackingUtilImpl
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent

/*
This file contains a series of extensions to the common module's Dependency injection (DI) graph. All extensions in this file simply add internal classes for this module into the DI graph.

The use of extensions was chosen over creating a separate graph class for each module. This simplifies the SDK code as well as automated tests code dramatically.
 */

internal val AndroidSDKComponent.fcmTokenProvider: DeviceTokenProvider
    get() = newInstance<DeviceTokenProvider> {
        FCMTokenProviderImpl(
            context = applicationContext,
            googleApiAvailabilityProvider = { GoogleApiAvailability.getInstance() },
            firebaseMessagingProvider = { FirebaseMessaging.getInstance() },
            pushLogger = SDKComponent.pushLogger
        )
    }

val SDKComponent.pushModuleConfig: MessagingPushModuleConfig
    get() = newInstance {
        modules[ModuleMessagingPushFCM.MODULE_NAME]?.moduleConfig as? MessagingPushModuleConfig ?: MessagingPushModuleConfig.default()
    }

internal val SDKComponent.deepLinkUtil: DeepLinkUtil
    get() = newInstance<DeepLinkUtil> { DeepLinkUtilImpl(logger, pushModuleConfig) }

@InternalCustomerIOApi
val SDKComponent.pushTrackingUtil: PushTrackingUtil
    get() = newInstance<PushTrackingUtil> { PushTrackingUtilImpl() }

internal val SDKComponent.pushMessageProcessor: PushMessageProcessor
    get() = singleton<PushMessageProcessor> {
        PushMessageProcessorImpl(
            logger = logger,
            moduleConfig = pushModuleConfig,
            deepLinkUtil = deepLinkUtil
        )
    }

internal val SDKComponent.httpClient: HttpClient
    get() = singleton<HttpClient> { HttpClientImpl() }

internal val SDKComponent.pushDeliveryTracker: PushDeliveryTracker
    get() = singleton<PushDeliveryTracker> { PushDeliveryTrackerImpl() }

internal val SDKComponent.pushLogger: PushNotificationLogger
    get() = singleton<PushNotificationLogger> { PushNotificationLogger(logger) }
