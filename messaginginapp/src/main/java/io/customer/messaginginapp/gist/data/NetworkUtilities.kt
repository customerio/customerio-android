package io.customer.messaginginapp.gist.data

class NetworkUtilities {
    companion object {
        internal const val CIO_SITE_ID_HEADER = "X-CIO-Site-Id"
        internal const val USER_TOKEN_HEADER = "X-Gist-Encoded-User-Token"
        internal const val CIO_DATACENTER_HEADER = "X-CIO-Datacenter"
        internal const val CIO_CLIENT_PLATFORM = "X-CIO-Client-Platform"
        internal const val CIO_CLIENT_VERSION = "X-CIO-Client-Version"
    }
}
