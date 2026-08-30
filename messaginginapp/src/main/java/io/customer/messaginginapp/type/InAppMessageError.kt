package io.customer.messaginginapp.type

/**
 * Why an in-app message failed to load or render.
 *
 * The entries are deliberately coarse: each maps to a different thing an integrator would do about
 * it — check connectivity, look at a slow renderer, report the message content to us, or file an
 * SDK bug. Finer detail belongs in [InAppMessageError.detail].
 */
enum class InAppMessageErrorReason {
    /** The renderer could not be reached: navigation failed, TLS failed, or the host errored. */
    NETWORK,

    /** The renderer was reached but never signalled that it had bootstrapped within the timeout. */
    TIMEOUT,

    /** The renderer loaded and then reported that it could not render the message. */
    RENDER_FAILED,

    /** The WebView's render process died, so the message can never finish. */
    WEB_VIEW_CRASHED,

    /** The SDK itself could not drive the render. */
    INTERNAL_ERROR
}

/**
 * A single in-app message load/render failure, with as much context as the failing layer had.
 *
 * @param reason the coarse category; branch on this.
 * @param detail human-readable detail from the layer that failed — a WebView error description, or
 * the message the renderer itself reported. Free-form and unstable: log it, don't parse it.
 * @param code the underlying platform error code where the failing layer had one, e.g. a
 * [android.webkit.WebResourceError] code or an HTTP status. Null when there was no numeric code.
 */
data class InAppMessageError(
    val reason: InAppMessageErrorReason,
    val detail: String? = null,
    val code: Int? = null
) {
    /**
     * Compact form for logs: `NETWORK (-2): net::ERR_NAME_NOT_RESOLVED`
     *
     * Internal: hosts should read [reason], [detail] and [code] rather than depend on this format.
     */
    internal fun describeForLogs(): String = buildString {
        append(reason.name)
        code?.let { append(" ($it)") }
        detail?.let { append(": $it") }
    }
}
