package io.customer.location.di

import io.customer.sdk.communication.LocationCache
import io.customer.sdk.core.di.SDKComponent

internal val SDKComponent.locationCache: LocationCache?
    get() = getOrNull()
