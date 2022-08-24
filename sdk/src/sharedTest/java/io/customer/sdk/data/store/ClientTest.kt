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
        val iOSClient = Client.Other(source = "iOS", sdkVersion = "4.6.7")

        iOSClient.toString().shouldBeEqualTo(expected = "iOS Client/4.6.7")
    }
}
