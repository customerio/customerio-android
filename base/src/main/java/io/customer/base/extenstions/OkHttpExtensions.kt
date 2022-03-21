package io.customer.base.extenstions

/**
 * A runtime exception "Unexpected char..." will be thrown by OkHttp if certain characters exist in the Header name or value of the HTTP request.
 *
 * See function for the release/tag that we have installed for OkHttp:
 * https://github.com/square/okhttp/blob/parent-4.9.1/okhttp/src/main/kotlin/okhttp3/Headers.kt#L431
 *
 * Here, we are removing all of the invalid characters to prevent OkHttp throwing an exception.
 */
fun String.filterHeaderValue(): String {
    var sanitizedString = ""
    for (character in this) {
        if (character == '\t' || character in '\u0020'..'\u007e') {
            sanitizedString = sanitizedString.plus(character)
        }
    }

    return sanitizedString
}
