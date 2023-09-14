package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseIntegrationTest
import io.customer.sdk.extensions.random
import io.customer.sdk.repository.CleanupRepository
import io.customer.sdk.repository.DeviceRepository
import io.customer.sdk.repository.ProfileRepository
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class CustomerIOTest : BaseIntegrationTest() {

    private val cleanupRepositoryMock: CleanupRepository = mock()
    private val deviceRepositoryMock: DeviceRepository = mock()
    private val profileRepositoryMock: ProfileRepository = mock()

    @Before
    fun setUp() {
        super.setup()

        di.overrideDependency(CleanupRepository::class.java, cleanupRepositoryMock)
        di.overrideDependency(DeviceRepository::class.java, deviceRepositoryMock)
        di.overrideDependency(ProfileRepository::class.java, profileRepositoryMock)
    }

    @Test
    fun deviceToken_testRegisterDeviceTokenWhenPreviouslyNull() {
        val givenDeviceToken = String.random

        CustomerIO.instance().registeredDeviceToken shouldBe null

        CustomerIO.instance().registerDeviceToken(givenDeviceToken)

        CustomerIO.instance().registeredDeviceToken shouldBeEqualTo givenDeviceToken
    }
}
