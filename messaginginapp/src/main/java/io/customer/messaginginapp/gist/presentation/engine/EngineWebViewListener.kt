package io.customer.messaginginapp.gist.presentation.engine

import io.customer.messaginginapp.type.InAppMessageError

interface EngineWebViewListener {
    fun bootstrapped()
    fun tap(name: String, action: String, system: Boolean)
    fun routeChanged(newRoute: String)
    fun routeError(route: String)
    fun routeLoaded(route: String)
    fun sizeChanged(width: Double, height: Double)
    fun error()

    /**
     * Called when a message fails to load or render, with the reason it failed.
     *
     * Defaulted so implementations written against the reason-less callback keep compiling.
     */
    fun error(error: InAppMessageError) = error()
}
