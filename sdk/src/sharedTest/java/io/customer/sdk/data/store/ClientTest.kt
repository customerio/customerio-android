package io.customer.sdk.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.utils.random
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClientTest : BaseTest() {
    private val sdkVersion = "${Int.random(1, 9)}.${Int.random(1, 9)}.${Int.random(1, 9)}"

    @Test
    fun initialize_givenSomeClient_expectCorrectStringTransformation() {
        val clientAndroid = Client.android(sdkVersion = "2.6.3")
        val clientExpo = Client.expo(sdkVersion = "3.9.7")
        val clientReactNative = Client.reactNative(sdkVersion = "7.3.2")
        val clientUnknown = Client.create(source = "iOS", sdkVersion = "4.6.7")

        clientAndroid.toString().shouldBeEqualTo(expected = "Android Client/2.6.3")
        clientExpo.toString().shouldBeEqualTo(expected = "Expo Client/3.9.7")
        clientReactNative.toString().shouldBeEqualTo(expected = "ReactNative Client/7.3.2")
        clientUnknown.toString().shouldBeEqualTo(expected = "iOS Client/4.6.7")
    }

    @Test
    fun initialize_givenAndroid_expectAndroidClient() {
        val clientName = Client.SOURCE_ANDROID
        val clientFromMethod = Client.android(sdkVersion = sdkVersion)
        val clientFromConstant = Client.create(source = clientName, sdkVersion = sdkVersion)
        val clientFromStringLowerCase = Client.create(source = "android", sdkVersion = sdkVersion)
        val clientFromStringUpperCase = Client.create(source = "ANDROID", sdkVersion = sdkVersion)
        val clientFromStringMixedCase = Client.create(source = "Android", sdkVersion = sdkVersion)

        clientFromMethod.source.shouldBeEqualTo(clientName)
        clientFromConstant.source.shouldBeEqualTo(clientName)
        clientFromStringLowerCase.source.shouldBeEqualTo(clientName)
        clientFromStringUpperCase.source.shouldBeEqualTo(clientName)
        clientFromStringMixedCase.source.shouldBeEqualTo(clientName)
    }

    @Test
    fun initialize_givenExpo_expectExpoClient() {
        val clientName = Client.SOURCE_EXPO
        val clientFromMethod = Client.expo(sdkVersion = sdkVersion)
        val clientFromConstant = Client.create(source = clientName, sdkVersion = sdkVersion)
        val clientFromStringLowerCase = Client.create(source = "expo", sdkVersion = sdkVersion)
        val clientFromStringUpperCase = Client.create(source = "EXPO", sdkVersion = sdkVersion)
        val clientFromStringMixedCase = Client.create(source = "Expo", sdkVersion = sdkVersion)

        clientFromMethod.source.shouldBeEqualTo(clientName)
        clientFromConstant.source.shouldBeEqualTo(clientName)
        clientFromStringLowerCase.source.shouldBeEqualTo(clientName)
        clientFromStringUpperCase.source.shouldBeEqualTo(clientName)
        clientFromStringMixedCase.source.shouldBeEqualTo(clientName)
    }

    @Test
    fun initialize_givenReactNative_expectReactNativeClient() {
        val clientName = Client.SOURCE_REACT_NATIVE
        val clientFromMethod = Client.reactNative(sdkVersion = sdkVersion)
        val clientFromConstant = Client.create(source = clientName, sdkVersion = sdkVersion)
        val clientFromStringLowerCase =
            Client.create(source = "reactnative", sdkVersion = sdkVersion)
        val clientFromStringUpperCase =
            Client.create(source = "REACTNATIVE", sdkVersion = sdkVersion)
        val clientFromStringMixedCase =
            Client.create(source = "ReactNative", sdkVersion = sdkVersion)

        clientFromMethod.source.shouldBeEqualTo(clientName)
        clientFromConstant.source.shouldBeEqualTo(clientName)
        clientFromStringLowerCase.source.shouldBeEqualTo(clientName)
        clientFromStringUpperCase.source.shouldBeEqualTo(clientName)
        clientFromStringMixedCase.source.shouldBeEqualTo(clientName)
    }

    @Test
    fun initialize_givenOther_expectOtherClient() {
        val clientFromStringLowerCase = Client.create(source = "ios", sdkVersion = sdkVersion)
        val clientFromStringUpperCase = Client.create(source = "IOS", sdkVersion = sdkVersion)
        val clientFromStringMixedCase = Client.create(source = "iOS", sdkVersion = sdkVersion)

        clientFromStringLowerCase.source.shouldBeEqualTo(expected = "ios")
        clientFromStringUpperCase.source.shouldBeEqualTo(expected = "IOS")
        clientFromStringMixedCase.source.shouldBeEqualTo(expected = "iOS")
    }
}
