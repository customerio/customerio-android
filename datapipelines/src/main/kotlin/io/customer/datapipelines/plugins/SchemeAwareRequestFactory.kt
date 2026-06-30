package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.RequestFactory
import java.net.HttpURLConnection

/**
 * Segment's [RequestFactory] always prepends "https://" to the configured apiHost/cdnHost.
 * When the host is configured with its own explicit scheme (e.g. "http://localhost:8080" used
 * for local/non-production testing), the built URL ends up doubly-schemed like
 * "https://http://localhost:8080/...". This factory collapses that back to the configured
 * scheme so https is not force-appended. Hosts without their own scheme are left untouched
 * (Segment's https:// stands).
 *
 * Both upload() (apiHost) and settings() (cdnHost) funnel through openConnection(String), so
 * overriding that single method covers every Segment request.
 */
internal class SchemeAwareRequestFactory : RequestFactory() {
    override fun openConnection(url: String): HttpURLConnection {
        // Strip a leading http(s):// only when it is immediately followed by another scheme.
        val normalized = LEADING_SCHEME_BEFORE_SCHEME.replaceFirst(url, "")
        return super.openConnection(normalized)
    }

    companion object {
        private val LEADING_SCHEME_BEFORE_SCHEME = Regex("^https?://(?=https?://)", RegexOption.IGNORE_CASE)
    }
}
