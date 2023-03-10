package io.customer.sdk.error

import com.squareup.moshi.JsonClass

/**
The API returns error response bodies in the format:
```
{"meta": { "error": "invalid id" }}
```
 */
@JsonClass(generateAdapter = true)
internal data class CustomerIOApiErrorResponse(
    val meta: Meta
) {

    // created property because Moshi cannot create adapter that extends Throwable
    val throwable: Throwable = Throwable(meta.error)

    @JsonClass(generateAdapter = true)
    data class Meta(
        val error: String
    )
}

/**
The API returns error response bodies in the format:
```
{"meta": { "errors": ["invalid id"] }}
```
 */
@JsonClass(generateAdapter = true)
internal data class CustomerIOApiErrorsResponse(
    val meta: Meta
) {

    // created property because Moshi cannot create adapter that extends Throwable
    val throwable: Throwable = Throwable(meta.errors.joinToString(", "))

    @JsonClass(generateAdapter = true)
    data class Meta(
        val errors: List<String>
    )
}
