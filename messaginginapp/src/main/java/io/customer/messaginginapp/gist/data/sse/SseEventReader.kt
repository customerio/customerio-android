package io.customer.messaginginapp.gist.data.sse

import java.io.EOFException
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8

/**
 * Reads the event fields used by Customer.io from a server-sent events response body.
 *
 * This intentionally depends only on Okio's public API. The OkHttp SSE artifact uses OkHttp
 * internals, which makes mismatched OkHttp and okhttp-sse versions unsafe in host applications.
 */
internal class SseEventReader(
    private val source: BufferedSource,
    private val onEvent: (type: String?, data: String) -> Unit
) {
    private var skipLeadingLf = false

    fun process() {
        var eventType: String? = null
        val data = StringBuilder()
        var isFirstLine = true
        var hasPendingField = false

        while (true) {
            var line = readLine()
            if (line == null) {
                if (hasPendingField) {
                    throw EOFException("SSE stream ended before the event delimiter")
                }
                return
            }
            if (isFirstLine) {
                line = line.removePrefix(UTF8_BOM)
                isFirstLine = false
            }

            if (line.isEmpty()) {
                dispatchEvent(eventType, data)
                eventType = null
                data.clear()
                hasPendingField = false
                continue
            }

            if (line.startsWith(COMMENT_PREFIX)) continue

            hasPendingField = true

            val separator = line.indexOf(FIELD_SEPARATOR)
            val field = if (separator == -1) line else line.substring(0, separator)
            var value = if (separator == -1) "" else line.substring(separator + 1)
            if (value.startsWith(" ")) value = value.substring(1)

            when (field) {
                EVENT_FIELD -> eventType = value
                DATA_FIELD -> data.append(value).append('\n')
            }
        }
    }

    /**
     * Reads an SSE line terminated by CR, LF, or CRLF.
     *
     * Okio's readUtf8Line only searches for LF, while the SSE specification permits all three
     * terminators. indexOfElement blocks until it finds either delimiter or reaches EOF.
     */
    private fun readLine(): String? {
        if (skipLeadingLf) {
            if (source.request(1) && source.buffer[0] == LINE_FEED) source.skip(1)
            skipLeadingLf = false
        }

        val newlineIndex = source.indexOfElement(NEWLINE_BYTES)
        if (newlineIndex == -1L) {
            return if (source.buffer.size == 0L) null else source.readUtf8()
        }

        val line = source.readUtf8(newlineIndex)
        val delimiter = source.readByte()
        // Return a CR-terminated line immediately. Looking ahead for LF here would block a
        // live CR-only stream until the server writes another byte.
        skipLeadingLf = delimiter == CARRIAGE_RETURN
        return line
    }

    private fun dispatchEvent(
        eventType: String?,
        data: StringBuilder
    ) {
        if (data.isEmpty()) return

        data.setLength(data.length - 1)
        onEvent(eventType, data.toString())
    }

    private companion object {
        const val COMMENT_PREFIX = ":"
        const val DATA_FIELD = "data"
        const val EVENT_FIELD = "event"
        const val FIELD_SEPARATOR = ':'
        const val UTF8_BOM = "\uFEFF"
        val NEWLINE_BYTES = "\r\n".encodeUtf8()
        val CARRIAGE_RETURN = '\r'.code.toByte()
        val LINE_FEED = '\n'.code.toByte()
    }
}
