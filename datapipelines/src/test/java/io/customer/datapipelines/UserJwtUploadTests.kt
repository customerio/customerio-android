package io.customer.datapipelines

import io.customer.datapipelines.network.UserJwtRequestFactory
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.identity.UserJwtStore
import io.customer.sdk.core.identity.userJwtStore
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test

class UserJwtUploadTests : JUnitTest() {

    @Test
    fun setUserJwt_storesTheTokenForItsUser() {
        sdkInstance.setUserJwt("alex", " jwt-1 ")

        SDKComponent.userJwtStore.current shouldBeEqualTo UserJwtStore.Entry("alex", "jwt-1")
    }

    @Test
    fun setUserJwt_givenBlankToken_clearsIt() {
        sdkInstance.setUserJwt("alex", "jwt-1")
        sdkInstance.setUserJwt("alex", "  ")

        SDKComponent.userJwtStore.current.shouldBeNull()
    }

    @Test
    fun clearIdentify_keepsTheTokenSoTheQueuedLogoutEventsStaySigned() {
        sdkInstance.identify("alex")
        sdkInstance.setUserJwt("alex", "jwt-1")

        sdkInstance.clearIdentify()

        SDKComponent.userJwtStore.current shouldBeEqualTo UserJwtStore.Entry("alex", "jwt-1")
    }

    @Test
    fun upload_givenToken_sendsItInTheHeader() {
        var token: String? = "jwt-1"
        val factory = UserJwtRequestFactory { token }

        factory.upload("cdp.example.com").getRequestProperty(UserJwtRequestFactory.USER_JWT_HEADER) shouldBeEqualTo "jwt-1"

        token = null
        factory.upload("cdp.example.com").getRequestProperty(UserJwtRequestFactory.USER_JWT_HEADER).shouldBeNull()
    }

    @Test
    fun userJwtStore_toString_neverPrintsTheToken() {
        UserJwtStore.Entry("alex", "secret-token").toString() shouldBeEqualTo "Entry(userId=alex)"
    }
}
