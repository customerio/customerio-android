package io.customer.messagingpush.di

import io.customer.messagingpush.provider.FCMTokenProviderImpl
import io.customer.sdk.device.DeviceTokenProvider
import io.customer.sdk.di.CustomerIOComponent

/*
This file contains a series of extensions to the common module's Dependency injection (DI) graph. All extensions in this file simply add internal classes for this module into the DI graph.

The use of extensions was chosen over creating a separate graph class for each module. This simplifies the SDK code as well as automated tests code dramatically.
 */

internal val CustomerIOComponent.fcmTokenProvider: DeviceTokenProvider
    get() = override() ?: FCMTokenProviderImpl(logger = logger, context = context)
