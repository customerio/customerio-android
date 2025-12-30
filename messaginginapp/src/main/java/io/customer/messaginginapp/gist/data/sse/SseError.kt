package io.customer.messaginginapp.gist.data.sse

import java.io.IOException
import okhttp3.Response

/**
 * Represents different types of SSE errors with their classification for retry logic.
 */
internal sealed class SseError(open val shouldRetry: Boolean) {
    data class NetworkError(val throwable: Throwable) : SseError(true)
    object TimeoutError : SseError(true)
    data class ServerError(val throwable: Throwable, val responseCode: Int, override val shouldRetry: Boolean) : SseError(shouldRetry)
    data class UnknownError(val throwable: Throwable?) : SseError(true)
}

/**
 * Classifies errors into SSE error types for appropriate retry handling.
 */
internal fun classifySseError(throwable: Throwable?, response: Response?): SseError {
    return when {
        throwable is IOException -> SseError.NetworkError(throwable)

        response != null -> {
            when (response.code) {
                408, 429, in 500..599 -> SseError.ServerError(
                    throwable ?: Exception("HTTP ${response.code}"),
                    response.code,
                    true
                )

                in 400..499 -> SseError.ServerError(
                    throwable ?: Exception("HTTP ${response.code}"),
                    response.code,
                    false
                )

                else -> SseError.ServerError(
                    throwable ?: Exception("HTTP ${response.code}"),
                    response.code,
                    true
                )
            }
        }

        else -> SseError.UnknownError(
            throwable ?: Exception("Unknown error")
        )
    }
}
