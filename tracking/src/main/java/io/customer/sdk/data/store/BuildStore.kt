package io.customer.sdk.data.store

import android.os.Build
import java.util.*

interface BuildStore {

    // Brand : Google
    val deviceBrand: String

    // Device model: Pixel
    val deviceModel: String

    // Hardware manufacturer: Samsung
    val deviceManufacturer: String

    // Android SDK Version: 21
    val deviceOSVersion: Int

    // Device locale: en-US
    val deviceLocale: String
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
    override val deviceLocale: String
        get() = Locale.getDefault().toLanguageTag()
}
