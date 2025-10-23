package io.customer.messaginginapp.gist

internal interface GistEnvironmentEndpoints {
    fun getGistQueueApiUrl(): String
    fun getEngineApiUrl(): String
    fun getGistRendererUrl(): String
    fun getSseApiUrl(): String
}

internal enum class GistEnvironment : GistEnvironmentEndpoints {
    DEV {
        override fun getGistQueueApiUrl() = "https://consumer.dev.inapp.customer.io"
        override fun getEngineApiUrl() = "https://engine.api.dev.gist.build"
        override fun getGistRendererUrl() = "https://renderer.gist.build/3.0"
        override fun getSseApiUrl() = "https://realtime.cloud.dev.gist.build/api/v3/sse"
    },

    LOCAL {
        override fun getGistQueueApiUrl() = "http://queue.api.local.gist.build:86"
        override fun getEngineApiUrl() = "http://engine.api.local.gist.build:82"
        override fun getGistRendererUrl() = "https://renderer.gist.build/3.0"
        override fun getSseApiUrl() = "https://realtime.cloud.dev.gist.build/api/v3/sse"
    },

    PROD {
        override fun getGistQueueApiUrl() = "https://consumer.inapp.customer.io"
        override fun getEngineApiUrl() = "https://engine.api.gist.build"
        override fun getGistRendererUrl() = "https://renderer.gist.build/3.0"
        override fun getSseApiUrl() = "https://realtime.inapp.customer.io/api/v3/sse"
    }
}
