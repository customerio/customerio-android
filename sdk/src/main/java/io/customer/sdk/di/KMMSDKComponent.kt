package io.customer.sdk.di

import android.content.Context
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.store.*
import io.customer.shared.AndroidPlatform
import io.customer.shared.Platform
import io.customer.shared.device.UserAgentStore
import io.customer.shared.di.SDKComponent
import io.customer.shared.sdk.config.NetworkConfig
import io.customer.shared.sdk.meta.Client
import io.customer.shared.sdk.meta.IdentityType
import io.customer.shared.sdk.meta.Region
import io.customer.shared.sdk.meta.Workspace
import io.customer.shared.serializer.CustomAttributeSerializer
import io.customer.shared.util.LogLevel

internal class KMMSDKComponent(
    private val appContext: Context,
    private val sdkConfig: CustomerIOConfig,
    override val customAttributeSerializer: CustomAttributeSerializer? = null
) : SDKComponent {
    override val customerIOConfig: io.customer.shared.sdk.config.CustomerIOConfig
        get() = with(sdkConfig) {
            io.customer.shared.sdk.config.CustomerIOConfig(
                sdkLogLevel = LogLevel.DEBUG,
                workspace = Workspace(
                    apiKey = apiKey,
                    siteId = siteId,
                    region = Region.fromRawValue(code = region.code),
                    client = Client.fromRawValue(
                        source = client.source,
                        sdkVersion = client.sdkVersion
                    ),
                    identityType = IdentityType.EMAIL
                ),
                network = with(NetworkConfig.Builder()) {
                    trackingApiUrl?.let { setTrackingApiUrl(it) }
                    build()
                }
            )
        }

    override val platform: Platform
        get() = AndroidPlatform(applicationContext = appContext)

    override val userAgentStore: UserAgentStore
        get() = buildStore().deviceStore

    internal fun buildStore(): CustomerIOStore {
        return object : CustomerIOStore {
            override val deviceStore: DeviceStore by lazy {
                DeviceStoreImp(
                    sdkConfig = sdkConfig,
                    buildStore = BuildStoreImp(),
                    applicationStore = ApplicationStoreImp(context = appContext),
                    version = sdkConfig.client.sdkVersion
                )
            }
        }
    }
}
