package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.testutils.core.JUnitTest
import java.io.IOException
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SseErrorTest : JUnitTest() {

    @Test
    fun testClassifySseError_whenIOException_thenReturnsNetworkError() {
        // Given
        val throwable = IOException("Network error")

        // When
        val result = classifySseError(throwable, null)

        // Then
        result.shouldBeInstanceOf<SseError.NetworkError>()
        (result as SseError.NetworkError).throwable.shouldBeEqualTo(throwable)
        result.shouldRetry.shouldBeEqualTo(true)
    }

    @Test
    fun testClassifySseError_whenHttp408_thenReturnsRetryableServerError() {
        // Given
        val response = createMockResponse(408)

        // When
        val result = classifySseError(null, response)

        // Then
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(408)
        serverError.shouldRetry.shouldBeEqualTo(true)
    }

    @Test
    fun testClassifySseError_whenHttp429_thenReturnsRetryableServerError() {
        // Given
        val response = createMockResponse(429)

        // When
        val result = classifySseError(null, response)

        // Then
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(429)
        serverError.shouldRetry.shouldBeEqualTo(true)
    }

    @Test
    fun testClassifySseError_whenHttp500_thenReturnsRetryableServerError() {
        // Given
        val response = createMockResponse(500)

        // When
        val result = classifySseError(null, response)

        // Then
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(500)
        serverError.shouldRetry.shouldBeEqualTo(true)
    }

    @Test
    fun testClassifySseError_whenHttp599_thenReturnsRetryableServerError() {
        // Given
        val response = createMockResponse(599)

        // When
        val result = classifySseError(null, response)

        // Then
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(599)
        serverError.shouldRetry.shouldBeEqualTo(true)
    }

    @Test
    fun testClassifySseError_whenHttp400_thenReturnsNonRetryableServerError() {
        // Given
        val response = createMockResponse(400)

        // When
        val result = classifySseError(null, response)

        // Then
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(400)
        serverError.shouldRetry.shouldBeEqualTo(false)
    }

    @Test
    fun testClassifySseError_whenHttp499_thenReturnsNonRetryableServerError() {
        // Given
        val response = createMockResponse(499)

        // When
        val result = classifySseError(null, response)

        // Then
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(499)
        serverError.shouldRetry.shouldBeEqualTo(false)
    }

    @Test
    fun testClassifySseError_whenHttp200_thenReturnsRetryableServerError() {
        // Given
        val response = createMockResponse(200)

        // When
        val result = classifySseError(null, response)

        // Then
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(200)
        serverError.shouldRetry.shouldBeEqualTo(true)
    }

    @Test
    fun testClassifySseError_whenNoResponseOrThrowable_thenReturnsUnknownError() {
        // When
        val result = classifySseError(null, null)

        // Then
        result.shouldBeInstanceOf<SseError.UnknownError>()
        result.shouldRetry.shouldBeEqualTo(true)
    }

    @Test
    fun testClassifySseError_whenNonIOException_thenReturnsUnknownError() {
        // Given
        val throwable = RuntimeException("Runtime error")

        // When
        val result = classifySseError(throwable, null)

        // Then
        result.shouldBeInstanceOf<SseError.UnknownError>()
        (result as SseError.UnknownError).throwable.shouldBeEqualTo(throwable)
        result.shouldRetry.shouldBeEqualTo(true)
    }

    @Test
    fun testClassifySseError_whenResponseWithThrowable_thenUsesResponseCode() {
        // Given
        val throwable = IOException("Network error")
        val response = createMockResponse(500)

        // When
        val result = classifySseError(throwable, response)

        result.shouldBeInstanceOf<SseError.NetworkError>()
        (result as SseError.NetworkError).throwable.shouldBeEqualTo(throwable)
        result.shouldRetry.shouldBeEqualTo(true)
    }

    @Test
    fun testClassifySseError_whenResponseWithNonIOException_thenUsesResponseCode() {
        // Given
        val throwable = RuntimeException("Runtime error")
        val response = createMockResponse(500)

        // When
        val result = classifySseError(throwable, response)

        // Then - Response takes precedence when throwable is not IOException
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(500)
        serverError.shouldRetry.shouldBeEqualTo(true)
        serverError.throwable.shouldBeEqualTo(throwable)
    }

    @Test
    fun testClassifySseError_whenHttp403_thenReturnsNonRetryableServerError() {
        // Given
        val response = createMockResponse(403)

        // When
        val result = classifySseError(null, response)

        // Then
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(403)
        serverError.shouldRetry.shouldBeEqualTo(false)
    }

    @Test
    fun testClassifySseError_whenHttp502_thenReturnsRetryableServerError() {
        // Given
        val response = createMockResponse(502)

        // When
        val result = classifySseError(null, response)

        // Then
        result.shouldBeInstanceOf<SseError.ServerError>()
        val serverError = result as SseError.ServerError
        serverError.responseCode.shouldBeEqualTo(502)
        serverError.shouldRetry.shouldBeEqualTo(true)
    }

    private fun createMockResponse(code: Int): Response {
        return Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.com").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("Test")
            .body("".toResponseBody(null))
            .build()
    }
}
