package io.customer.messaginginapp.support

import com.google.gson.Gson
import io.customer.messaginginapp.gist.data.model.Message
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * Dispatcher to mock Gist server responses for user messages and renderer requests.
 */
class GistServerResponseDispatcher : Dispatcher() {
    private val emptyResponse = MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_OK)
        .setBody("EMPTY RESPONSE")
    private var userMessagesMockResponse: MockResponse? = null
    private var rendererMockResponse: MockResponse? = null

    init {
        reset()
    }

    fun reset() {
        userMessagesMockResponse = getQueueMessagesMock(emptyList())
        rendererMockResponse = getInAppMessageHtmlMock()
    }

    fun setUserMessagesMockResponse(block: GistServerResponseDispatcher.() -> MockResponse) {
        userMessagesMockResponse = block(this)
    }

    fun setRendererMockResponse(block: GistServerResponseDispatcher.() -> MockResponse) {
        rendererMockResponse = block(this)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val requestUrl = request.requestUrl
        val pathSegments = requestUrl?.pathSegments

        val mockResponse = when {
            request.path == "/favicon.ico" -> emptyResponse

            pathSegments?.firstOrNull() == "api" -> when {
                requestUrl.encodedPath.contains("api/v1/users") -> userMessagesMockResponse
                else -> null
            } ?: emptyResponse

            pathSegments?.firstOrNull() == "renderer" -> requestUrl.queryParameter("options")?.let { options ->
                val message = decodeOptionsString(options)
                if (message.messageId.isBlank()) {
                    return@let getInAppMessageHtmlMock(delay = 0)
                }
                return@let rendererMockResponse
            }

            else -> null
        }
        return mockResponse ?: MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
            .setBody("Not Found")
    }

    fun getQueueMessagesMock(
        messages: List<Message>
    ): MockResponse = MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_OK)
        .setBody(Gson().toJson(messages))

    private fun getInAppMessageHtmlMock(delay: Long = 200L): MockResponse {
        val htmlContent = """
            <html>
            <head><title>Mocked Message</title></head>
            <body>
            </body>
            </html>
        """.trimIndent()

        val encodedContent = htmlContent.toByteArray(Charsets.UTF_8)
        return MockResponse()
            .addHeader("Content-Type", "text/html; charset=utf-8")
            .addHeader("Content-Length", encodedContent.size.toString())
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(Buffer().write(encodedContent))
            .setBodyDelay(delay, TimeUnit.MILLISECONDS)
    }
}
