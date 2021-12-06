package io.customer.sdk.data.store

import android.os.Build

interface BuildStore {

    // Brand : Google
    val deviceBrand: String

    // Device model: Pixel
    val deviceModel: String

    // Hardware manufacturer: Samsung
    val deviceManufacturer: String

    // Android SDK Version: 21
    val deviceOSVersion: Int
}

internal class BuildStoreImp : BuildStore {

    override val deviceBrand: String
        get() = Build.BRAND
    override val deviceModel: String
        get() = Build.MODEL
    override val deviceManufacturer: String
        get() = Build.MANUFACTURER
    override val deviceOSVersion: Int
        get() = Build.VERSION.SDK_INT
}
