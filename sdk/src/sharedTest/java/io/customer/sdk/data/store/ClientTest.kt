package io.customer.sdk.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClientTest : BaseTest() {
    @Test
    fun initialize_givenAndroid_expectAndroidClient() {
        val androidClient = Client.Android(sdkVersion = "2.6.3")

        androidClient.toString().shouldBeEqualTo(expected = "Android Client/2.6.3")
    }

    @Test
    fun initialize_givenExpo_expectExpoClient() {
        val expoClient = Client.Expo(sdkVersion = "3.9.7")

        expoClient.toString().shouldBeEqualTo(expected = "Expo Client/3.9.7")
    }

    @Test
    fun initialize_givenReactNative_expectReactNativeClient() {
        val reactNativeClient = Client.ReactNative(sdkVersion = "7.3.2")

        reactNativeClient.toString().shouldBeEqualTo(expected = "ReactNative Client/7.3.2")
    }

    @Test
    fun initialize_givenOther_expectOtherClient() {
        val iOSClient = Client.fromRawValue(source = "iOS", sdkVersion = "4.6.7")

        iOSClient.toString().shouldBeEqualTo(expected = "iOS Client/4.6.7")
    }

    @Test
    fun initialize_givenRawValueAndroid_expectAndroidClient() {
        val lowerCaseClient = Client.fromRawValue(source = "android", sdkVersion = "1.2.3")
        val upperCaseClient = Client.fromRawValue(source = "ANDROID", sdkVersion = "2.3.4")
        val titleCaseClient = Client.fromRawValue(source = "Android", sdkVersion = "3.4.5")
        val mixedCaseClient = Client.fromRawValue(source = "AndRoid", sdkVersion = "4.5.6")

        lowerCaseClient.toString().shouldBeEqualTo(expected = "Android Client/1.2.3")
        upperCaseClient.toString().shouldBeEqualTo(expected = "Android Client/2.3.4")
        titleCaseClient.toString().shouldBeEqualTo(expected = "Android Client/3.4.5")
        mixedCaseClient.toString().shouldBeEqualTo(expected = "Android Client/4.5.6")
    }

    @Test
    fun initialize_givenRawValueReactNative_expectReactNativeClient() {
        val lowerCaseClient = Client.fromRawValue(source = "reactnative", sdkVersion = "1.2.3")
        val upperCaseClient = Client.fromRawValue(source = "REACTNATIVE", sdkVersion = "2.3.4")
        val titleCaseClient = Client.fromRawValue(source = "ReactNative", sdkVersion = "3.4.5")
        val mixedCaseClient = Client.fromRawValue(source = "REACTNative", sdkVersion = "4.5.6")

        lowerCaseClient.toString().shouldBeEqualTo(expected = "ReactNative Client/1.2.3")
        upperCaseClient.toString().shouldBeEqualTo(expected = "ReactNative Client/2.3.4")
        titleCaseClient.toString().shouldBeEqualTo(expected = "ReactNative Client/3.4.5")
        mixedCaseClient.toString().shouldBeEqualTo(expected = "ReactNative Client/4.5.6")
    }

    @Test
    fun initialize_givenRawValueExpo_expectExpoClient() {
        val lowerCaseClient = Client.fromRawValue(source = "expo", sdkVersion = "1.2.3")
        val upperCaseClient = Client.fromRawValue(source = "EXPO", sdkVersion = "2.3.4")
        val titleCaseClient = Client.fromRawValue(source = "Expo", sdkVersion = "3.4.5")
        val mixedCaseClient = Client.fromRawValue(source = "ExpO", sdkVersion = "4.5.6")

        lowerCaseClient.toString().shouldBeEqualTo(expected = "Expo Client/1.2.3")
        upperCaseClient.toString().shouldBeEqualTo(expected = "Expo Client/2.3.4")
        titleCaseClient.toString().shouldBeEqualTo(expected = "Expo Client/3.4.5")
        mixedCaseClient.toString().shouldBeEqualTo(expected = "Expo Client/4.5.6")
    }

    @Test
    fun initialize_givenRawValueOther_expectOtherClient() {
        val lowerCaseClient = Client.fromRawValue(source = "ios", sdkVersion = "1.2.3")
        val upperCaseClient = Client.fromRawValue(source = "IOS", sdkVersion = "2.3.4")
        val titleCaseClient = Client.fromRawValue(source = "Ios", sdkVersion = "3.4.5")
        val mixedCaseClient = Client.fromRawValue(source = "iOS", sdkVersion = "4.5.6")

        lowerCaseClient.toString().shouldBeEqualTo(expected = "ios Client/1.2.3")
        upperCaseClient.toString().shouldBeEqualTo(expected = "IOS Client/2.3.4")
        titleCaseClient.toString().shouldBeEqualTo(expected = "Ios Client/3.4.5")
        mixedCaseClient.toString().shouldBeEqualTo(expected = "iOS Client/4.5.6")
    }
}
