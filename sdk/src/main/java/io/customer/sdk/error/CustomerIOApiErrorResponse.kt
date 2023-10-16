@file:UseContextualSerialization(Any::class)

package io.customer.sdk.error

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

/**
The API returns error response bodies in the format:
```
{"meta": { "error": "invalid id" }}
```
 */
@Serializable
internal data class CustomerIOApiErrorResponse(
    val meta: Meta
) {

    // created property because Moshi cannot create adapter that extends Throwable
    @Contextual
    val throwable: Throwable = Throwable(meta.error)

    @Serializable
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
@Serializable
internal data class CustomerIOApiErrorsResponse(
    val meta: Meta
) {

    // created property because Moshi cannot create adapter that extends Throwable
    @Contextual
    val throwable: Throwable = Throwable(meta.errors.joinToString(", "))

    @Serializable
    data class Meta(
        val errors: List<String>
    )
}
