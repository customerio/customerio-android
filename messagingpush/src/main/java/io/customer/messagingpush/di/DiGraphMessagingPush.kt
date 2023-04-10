package io.customer.messagingpush.di

import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.provider.FCMTokenProviderImpl
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.DeepLinkUtilImpl
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.messagingpush.util.PushTrackingUtilImpl
import io.customer.sdk.device.DeviceTokenProvider
import io.customer.sdk.di.CustomerIOComponent

/*
This file contains a series of extensions to the common module's Dependency injection (DI) graph. All extensions in this file simply add internal classes for this module into the DI graph.

The use of extensions was chosen over creating a separate graph class for each module. This simplifies the SDK code as well as automated tests code dramatically.
 */

internal val CustomerIOComponent.fcmTokenProvider: DeviceTokenProvider
    get() = override() ?: FCMTokenProviderImpl(logger = logger, context = context)

internal val CustomerIOComponent.moduleConfig: MessagingPushModuleConfig
    get() = override()
        ?: sdkConfig.modules[ModuleMessagingPushFCM.MODULE_NAME]?.moduleConfig as? MessagingPushModuleConfig
        ?: MessagingPushModuleConfig.default()

internal val CustomerIOComponent.deepLinkUtil: DeepLinkUtil
    get() = override() ?: DeepLinkUtilImpl(logger, moduleConfig)

internal val CustomerIOComponent.pushTrackingUtil: PushTrackingUtil
    get() = override() ?: PushTrackingUtilImpl(trackRepository)
