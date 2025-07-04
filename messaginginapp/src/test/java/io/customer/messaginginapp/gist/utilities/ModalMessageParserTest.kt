package io.customer.messaginginapp.gist.utilities

import android.content.Intent
import com.google.gson.Gson
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertNotOnMainThread
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModalMessageParserTest : IntegrationTest() {
    private val mockLogger = mockk<Logger>(relaxed = true)
    private val dispatchersProviderStub = spyk(DispatchersProviderStub())
    private val gson = Gson()

    private val parser = ModalMessageParserDefault(
        logger = mockLogger,
        dispatchersProvider = dispatchersProviderStub,
        parser = ModalMessageGsonParser(gson = gson)
    )

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(mockLogger)
                        overrideDependency<DispatchersProvider>(dispatchersProviderStub)
                    }
                }
            }
        )
    }

    @Test
    fun parseExtras_givenValidMessageAndPosition_expectCorrectParsing() = runTest {
        val givenMessage = createInAppMessage(
            messageId = "test-123",
            priority = 1,
            position = MessagePosition.CENTER.name
        )

        val mockIntent = createMockIntentWithExtras(
            message = gson.toJson(givenMessage),
            position = MessagePosition.TOP.name
        )

        val result = parser.parseExtras(mockIntent).shouldNotBeNull()
        result.message shouldBeEqualTo givenMessage
        result.messagePosition shouldBeEqualTo MessagePosition.TOP
    }

    @Test
    fun parseExtras_givenValidMessageWithoutPosition_expectDefaultPosition() = runTest {
        val givenMessage = createInAppMessage(
            messageId = "test-456",
            priority = 2,
            position = MessagePosition.BOTTOM.name
        )

        val mockIntent = createMockIntentWithExtras(
            message = gson.toJson(givenMessage),
            position = null
        )

        val result = parser.parseExtras(mockIntent).shouldNotBeNull()
        result.message shouldBeEqualTo givenMessage
        result.messagePosition shouldBeEqualTo MessagePosition.BOTTOM // From message gist properties
    }

    @Test
    fun parseExtras_givenNullMessage_expectNullResultAndError() = runTest {
        val mockIntent = createMockIntentWithExtras(
            message = null,
            position = MessagePosition.CENTER.name
        )

        val result = parser.parseExtras(mockIntent)

        result.shouldBeNull()
        verify { mockLogger.error("ModalMessageParser: Message is null or empty") }
    }

    @Test
    fun parseExtras_givenEmptyMessage_expectNullResultAndError() = runTest {
        val mockIntent = createMockIntentWithExtras(
            message = "",
            position = MessagePosition.CENTER.name
        )

        val result = parser.parseExtras(mockIntent)

        result.shouldBeNull()
        verify { mockLogger.error("ModalMessageParser: Message is null or empty") }
    }

    @Test
    fun parseExtras_givenInvalidJson_expectNullResultAndError() = runTest {
        val mockIntent = createMockIntentWithExtras(
            message = "invalid-json-{",
            position = MessagePosition.CENTER.name
        )

        val result = parser.parseExtras(mockIntent)

        result.shouldBeNull()
        verify { mockLogger.error(match { it.contains("Failed to parse modal message with error") }) }
    }

    @Test
    fun parseExtras_givenInvalidPosition_expectExceptionHandling() = runTest {
        val givenMessage = Message(messageId = "test-789", priority = 2)

        val mockIntent = createMockIntentWithExtras(
            message = gson.toJson(givenMessage),
            position = "INVALID_POSITION"
        )

        val result = parser.parseExtras(mockIntent)

        result.shouldBeNull()
        verify { mockLogger.error(match { it.contains("Failed to parse modal message with error") }) }
    }

    @Test
    fun parseExtras_givenCaseInsensitivePosition_expectCorrectParsing() = runTest {
        val givenMessage = createInAppMessage(
            messageId = "test-case",
            priority = 1,
            position = MessagePosition.CENTER.name
        )

        val mockIntent = createMockIntentWithExtras(
            message = gson.toJson(givenMessage),
            position = "bottom" // lowercase should work
        )

        val result = parser.parseExtras(mockIntent)

        result?.messagePosition shouldBeEqualTo MessagePosition.BOTTOM
    }

    @Test
    fun parseExtras_givenPositionPriorityOrder_expectExtrasOverrideMessagePosition() = runTest {
        val givenMessage = createInAppMessage(
            messageId = "priority-test",
            position = MessagePosition.CENTER.name // Message default
        )

        val mockIntent = createMockIntentWithExtras(
            message = gson.toJson(givenMessage),
            position = MessagePosition.BOTTOM.name // Extras override
        )

        val result = parser.parseExtras(mockIntent).shouldNotBeNull()
        // Extras position should override message position
        result.messagePosition shouldBeEqualTo MessagePosition.BOTTOM
        // But message still contains its original gist properties
        result.message.gistProperties.position shouldBeEqualTo MessagePosition.CENTER
    }

    @Test
    fun parseExtras_givenNullProvider_expectGracefulHandling() = runTest {
        val mockIntent = createMockIntentWithExtras(null, null)

        val result = parser.parseExtras(mockIntent)

        result.shouldBeNull()
        verify { mockLogger.error("ModalMessageParser: Message is null or empty") }
    }

    @Test
    fun parseExtras_givenSpiedDispatcher_expectBackgroundDispatcherUsed() = runTest {
        dispatchersProviderStub.setRealDispatchers()
        val jsonParser = object : ModalMessageParser.JsonParser {
            override fun parseMessageFromJson(json: String): Message? {
                assertNotOnMainThread()
                return gson.fromJson(json, Message::class.java)
            }
        }
        val messageParser = ModalMessageParserDefault(
            logger = mockLogger,
            dispatchersProvider = dispatchersProviderStub,
            parser = jsonParser
        )
        val givenMessage = createInAppMessage(
            messageId = "threading-test",
            priority = 1
        )

        val mockIntent = createMockIntentWithExtras(
            message = gson.toJson(givenMessage),
            position = MessagePosition.CENTER.name
        )

        val result = messageParser.parseExtras(mockIntent).shouldNotBeNull()
        // Compare specific fields instead of entire object - Gson deserializes nested Maps as LinkedTreeMap
        // while createInAppMessage uses different Map types, causing equals() to fail despite same data
        result.message.messageId shouldBeEqualTo givenMessage.messageId
        result.message.priority shouldBeEqualTo givenMessage.priority
        result.messagePosition shouldBeEqualTo MessagePosition.CENTER
        dispatchersProviderStub.resetToTestDispatchers()
    }

    private fun createMockIntentWithExtras(
        message: String?,
        position: String?
    ): Intent = mockk<Intent>(relaxed = true).apply {
        every { getStringExtra(ModalMessageParser.EXTRA_IN_APP_MESSAGE) } returns message
        every { getStringExtra(ModalMessageParser.EXTRA_IN_APP_MODAL_POSITION) } returns position
    }
}
