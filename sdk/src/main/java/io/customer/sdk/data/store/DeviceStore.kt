package io.customer.sdk.data.store

interface DeviceStore : BuildStore, ApplicationStore {

    // SDK version
    val customerIOVersion: String

    /**
     * buildUserAgent - To get `user-agent` header value. This value depends on SDK version
     * and device detail such as OS version, device model, customer's app name etc
     *
     * If the device and OS information is available, it will return in following format :
     * `Customer.io Android Client/1.0.0-alpha.6 (Google Pixel 6; 30) User App/1.0`
     *
     * Otherwise will return
     * `Customer.io Android Client/1.0.0-alpha.6`
     */
    fun buildUserAgent(): String
}

internal class DeviceStoreImp(
    private val buildStore: BuildStore,
    private val applicationStore: ApplicationStore,
    private val version: String
) : DeviceStore {

    override val deviceBrand: String
        get() = buildStore.deviceBrand
    override val deviceModel: String
        get() = buildStore.deviceModel
    override val deviceManufacturer: String
        get() = buildStore.deviceManufacturer
    override val deviceOSVersion: Int
        get() = buildStore.deviceOSVersion
    override val customerAppName: String
        get() = applicationStore.customerAppName
    override val customerAppVersion: String
        get() = applicationStore.customerAppVersion
    override val customerIOVersion: String
        get() = version

    override fun buildUserAgent(): String {
        return buildString {
            append("Customer.io Android Client/")
            append(customerIOVersion)
            append(" ($deviceManufacturer $deviceModel; $deviceOSVersion)")
            append(" $customerAppName/$customerAppVersion")
        }
    }
}
