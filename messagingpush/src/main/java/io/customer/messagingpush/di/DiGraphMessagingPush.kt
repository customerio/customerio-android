@file:OptIn(InternalCustomerIOApi::class)

package io.customer.messagingpush.di

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.processor.PushMessageProcessor
import io.customer.messagingpush.processor.PushMessageProcessorImpl
import io.customer.messagingpush.provider.FCMTokenProviderImpl
import io.customer.messagingpush.util.AppLifecycleCallbacks
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.DeepLinkUtilImpl
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.messagingpush.util.PushTrackingUtilImpl
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.device.DeviceTokenProvider

/*
This file contains a series of extensions to the common module's Dependency injection (DI) graph. All extensions in this file simply add internal classes for this module into the DI graph.

The use of extensions was chosen over creating a separate graph class for each module. This simplifies the SDK code as well as automated tests code dramatically.
 */

internal val AndroidSDKComponent.fcmTokenProvider: DeviceTokenProvider
    get() = newInstance<DeviceTokenProvider> { FCMTokenProviderImpl(context = context) }

internal val SDKComponent.moduleConfig: MessagingPushModuleConfig
    get() = newInstance { modules[ModuleMessagingPushFCM.MODULE_NAME]?.moduleConfig as? MessagingPushModuleConfig ?: MessagingPushModuleConfig.default() }

internal val SDKComponent.deepLinkUtil: DeepLinkUtil
    get() = newInstance { DeepLinkUtilImpl(logger, moduleConfig) }

@InternalCustomerIOApi
val SDKComponent.pushTrackingUtil: PushTrackingUtil
    get() = newInstance { PushTrackingUtilImpl() }

val SDKComponent.appLifecycleCallbacks: AppLifecycleCallbacks
    get() = newInstance { AppLifecycleCallbacks() }

internal val SDKComponent.pushMessageProcessor: PushMessageProcessor
    get() = singleton {
        PushMessageProcessorImpl(
            logger = logger,
            moduleConfig = moduleConfig,
            deepLinkUtil = deepLinkUtil
        )
    }

fun CustomerIO.pushMessaging(): ModuleMessagingPushFCM {
    return diGraph.sdkConfig.modules[ModuleMessagingPushFCM.MODULE_NAME] as? ModuleMessagingPushFCM
        ?: throw IllegalStateException("ModuleMessagingPushFCM not initialized")
}
