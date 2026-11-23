package io.customer.datapipelines.network

import com.segment.analytics.kotlin.core.RequestFactory
import java.net.HttpURLConnection

/**
 * Sends the signed user JWT with each event upload, for workspaces that require one. An upload can
 * hold events from before the user changed; the server vouches only for events whose `userId` is
 * the token's `uid`, so the current token never speaks for anyone else.
 */
internal class UserJwtRequestFactory(private val token: () -> String?) : RequestFactory() {
    override fun upload(apiHost: String): HttpURLConnection = super.upload(apiHost).apply {
        token()?.let { setRequestProperty(USER_JWT_HEADER, it) }
    }

    companion object {
        const val USER_JWT_HEADER = "X-CIO-User-JWT"
    }
}
