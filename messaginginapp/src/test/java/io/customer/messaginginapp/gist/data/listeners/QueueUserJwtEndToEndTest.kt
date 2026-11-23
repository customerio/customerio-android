package io.customer.messaginginapp.gist.data.listeners

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.inbox.data.InboxFetchOutcome
import io.customer.messaginginapp.inbox.data.InboxRepository
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.type.UserJwtRejectionReason
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.coEvery
import io.mockk.mockk
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Runs the real queue client against a stub Queue Consumer that enforces the user JWT the way Gist
 * does for an enforced organization: RS256 only, `exp` required with 60 seconds of leeway, `sub`
 * of the form `cio_<hex>`, a 401 with `{"error":"user_jwt_rejected","reason":...}` on failure, and
 * the queue of the person `sub` names on success.
 */
@RunWith(RobolectricTestRunner::class)
class QueueUserJwtEndToEndTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Unconfined()
    private val mockRepository: InboxRepository = mockk(relaxed = true)
    private val workspaceKey: KeyPair = rsaKeyPair()
    private val otherKey: KeyPair = rsaKeyPair()
    private val server = MockWebServer()
    private val consumer = StubQueueConsumer(workspaceKey.public)
    private val rejections = mutableListOf<UserJwtRejectionReason>()

    private lateinit var manager: InAppMessagingManager
    private lateinit var module: ModuleMessagingInApp
    private lateinit var queue: Queue

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<InboxRepository>(mockRepository)
                        overrideDependency<GistProvider>(mockk(relaxed = true))
                    }
                }
            } + testConfig
        )
        coEvery { mockRepository.loadTemplatesAndBranding() } returns InboxFetchOutcome.Hidden("test")
        server.dispatcher = consumer
        server.start()

        module = ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(siteId = TestConstants.Keys.SITE_ID)
                .setUserJwtListener { _, reason -> rejections += reason }
                .build()
        ).attachToSDKComponent()

        manager = SDKComponent.inAppMessagingManager
        manager.dispatch(InAppMessagingAction.Initialize(siteId = "site", dataCenter = "us", environment = GistEnvironment.PROD))
        manager.dispatch(InAppMessagingAction.SetUserIdentifier(USER_ID))

        // The real client, interceptor included, pointed at the stub.
        val service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .client(Queue().httpClient)
            .build()
            .create(GistQueueService::class.java)
        queue = Queue(queueServiceOverride = service)
    }

    override fun teardown() {
        manager.dispatch(InAppMessagingAction.Reset)
        server.shutdown()
        super.teardown()
    }

    // The client handles the stub's answer on OkHttp's thread, so wait for its effect.
    private fun fetch(until: () -> Boolean) {
        queue.fetchUserMessages()
        val deadline = System.currentTimeMillis() + 5_000
        while (!until() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun inboxQueueIds(): Set<String> = manager.getCurrentState().inboxMessages.map { it.queueId }.toSet()

    @Test
    fun enforced_givenNoJwt_expectMissingAndNothingServed() {
        fetch { rejections.isNotEmpty() }

        assertEquals(listOf(UserJwtRejectionReason.MISSING), rejections)
        assertTrue(inboxQueueIds().isEmpty())
    }

    @Test
    fun enforced_givenValidJwt_expectThatPersonsInboxServed() {
        module.setUserJwt(USER_ID, sign(workspaceKey, sub = "cio_$CIO_ID"))
        fetch { inboxQueueIds().isNotEmpty() }

        assertTrue(rejections.isEmpty())
        assertEquals(setOf("queue-$CIO_ID"), inboxQueueIds())
    }

    @Test
    fun enforced_givenValidJwt_expectJwtSentWhenMarkingInboxOpened() {
        module.setUserJwt(USER_ID, sign(workspaceKey, sub = "cio_$CIO_ID"))
        fetch { inboxQueueIds().isNotEmpty() }
        server.takeRequest(5, TimeUnit.SECONDS)

        val message = manager.getCurrentState().inboxMessages.single()
        runBlocking { queue.logOpenedStatus(message, opened = true) }
        val patch = server.takeRequest(5, TimeUnit.SECONDS)

        assertEquals("PATCH", patch?.method)
        assertEquals(200, consumer.lastStatus)
    }

    @Test
    fun enforced_givenEachBadJwt_expectItsReason() {
        val cases = listOf(
            "abc.def" to UserJwtRejectionReason.MALFORMED,
            sign(workspaceKey, sub = "cio_$CIO_ID", exp = null) to UserJwtRejectionReason.MALFORMED,
            sign(otherKey, sub = "cio_$CIO_ID") to UserJwtRejectionReason.BAD_SIGNATURE,
            unsigned(sub = "cio_$CIO_ID") to UserJwtRejectionReason.BAD_SIGNATURE,
            sign(workspaceKey, sub = "cio_$CIO_ID", exp = now() - 61) to UserJwtRejectionReason.EXPIRED,
            sign(workspaceKey, sub = USER_ID) to UserJwtRejectionReason.BAD_SUBJECT
        )

        cases.forEachIndexed { index, (jwt, _) ->
            module.setUserJwt(USER_ID, jwt)
            fetch { rejections.size > index }
        }

        assertEquals(cases.map { it.second }, rejections)
        assertTrue(inboxQueueIds().isEmpty())
    }

    @Test
    fun enforced_givenExpiredThenFreshJwt_expectRecovered() {
        module.setUserJwt(USER_ID, sign(workspaceKey, sub = "cio_$CIO_ID", exp = now() - 61))
        fetch { rejections.isNotEmpty() }
        // Paused: no request leaves until the app hands over a new token.
        queue.fetchUserMessages()
        assertEquals(1, consumer.requestCount)

        module.setUserJwt(USER_ID, sign(workspaceKey, sub = "cio_$CIO_ID"))
        fetch { inboxQueueIds().isNotEmpty() }

        assertEquals(listOf(UserJwtRejectionReason.EXPIRED), rejections)
        assertEquals(setOf("queue-$CIO_ID"), inboxQueueIds())
    }

    @Test
    fun notEnforced_givenNoJwt_expectServedAsToday() {
        consumer.enforced = false
        fetch { inboxQueueIds().isNotEmpty() }

        assertTrue(rejections.isEmpty())
        assertEquals(setOf("queue-by-user-token"), inboxQueueIds())
    }

    private class StubQueueConsumer(private val key: PublicKey) : Dispatcher() {
        @Volatile var enforced = true

        @Volatile var lastStatus = 0

        @Volatile var requestCount = 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            requestCount++
            val response = respond(request)
            lastStatus = response.status.split(" ")[1].toInt()
            return response
        }

        private fun respond(request: RecordedRequest): MockResponse {
            val cioId = if (enforced) {
                when (val result = validate(request.getHeader(NetworkUtilities.USER_JWT_HEADER))) {
                    is Result.Rejected -> return MockResponse().setResponseCode(401)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"error":"user_jwt_rejected","reason":"${result.reason}"}""")
                    is Result.Valid -> result.cioId
                }
            } else {
                null
            }
            if (request.method == "PATCH") return MockResponse().setResponseCode(200)
            val queueId = cioId?.let { "queue-$it" } ?: "queue-by-user-token"
            return MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"inAppMessages":[],"inboxMessages":[{"queueId":"$queueId","sentAt":"2026-11-20T10:00:00Z","type":"inbox"}]}""")
        }

        private sealed class Result {
            data class Valid(val cioId: String) : Result()
            data class Rejected(val reason: String) : Result()
        }

        private fun validate(jwt: String?): Result {
            if (jwt.isNullOrEmpty()) return Result.Rejected("missing")
            val parts = jwt.split(".")
            if (parts.size != 3) return Result.Rejected("malformed")
            val header = decodeJson(parts[0]) ?: return Result.Rejected("malformed")
            val payload = decodeJson(parts[1]) ?: return Result.Rejected("malformed")
            val exp = payload.get("exp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            if (header.get("alg") == null || exp == null) return Result.Rejected("malformed")
            if (header.get("alg").asString != "RS256" || !verifies(parts)) return Result.Rejected("bad_signature")
            if (now() > exp + 60) return Result.Rejected("expired")
            val sub = payload.get("sub")?.asString.orEmpty()
            if (!Regex("^cio_[0-9a-f]+$").matches(sub)) return Result.Rejected("bad_subject")
            return Result.Valid(sub.removePrefix("cio_"))
        }

        private fun verifies(parts: List<String>): Boolean = try {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray())
                verify(Base64.getUrlDecoder().decode(parts[2]))
            }
        } catch (e: Exception) {
            false
        }

        private fun decodeJson(part: String) = try {
            Gson().fromJson(String(Base64.getUrlDecoder().decode(part)), JsonObject::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val USER_ID = "user-1"
        const val CIO_ID = "1a2b3c"

        fun now(): Long = System.currentTimeMillis() / 1000

        fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun payload(sub: String, exp: Long?): String =
            b64(("""{"sub":"$sub"""" + (exp?.let { ""","exp":$it""" } ?: "") + "}").toByteArray())

        fun sign(key: KeyPair, sub: String, exp: Long? = now() + 300): String {
            val signingInput = b64("""{"alg":"RS256","typ":"JWT"}""".toByteArray()) + "." + payload(sub, exp)
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(key.private)
                update(signingInput.toByteArray())
                sign()
            }
            return "$signingInput.${b64(signature)}"
        }

        fun unsigned(sub: String): String =
            b64("""{"alg":"none"}""".toByteArray()) + "." + payload(sub, now() + 300) + "."
    }
}
