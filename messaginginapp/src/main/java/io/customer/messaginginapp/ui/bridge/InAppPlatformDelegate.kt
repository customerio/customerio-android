package io.customer.messaginginapp.ui.bridge

import android.content.Context
import android.content.Intent
import android.net.UrlQuerySanitizer
import android.util.Base64
import android.util.DisplayMetrics
import android.view.View
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.gson.Gson
import io.customer.messaginginapp.ui.extensions.animateViewSize
import io.customer.messaginginapp.ui.extensions.findActivity
import java.net.URI
import java.nio.charset.StandardCharsets

import io.customer.base.internal.InternalCustomerIOApi

/**
 * Delegate interface to abstract Android platform operations used by in-app messaging views.
 * Encapsulates operations like URI parsing, URL sanitization, Base64 decoding, view animations,
 * activity launching, and utility conversions.
 * Allows for easier testing and mocking of platform-specific behavior.
 */
@InternalCustomerIOApi
interface InAppPlatformDelegate {
    fun parseJavaURI(uriString: String): URI
    fun sanitizeUrlQuery(url: String): UrlQuerySanitizer
    fun parsePropertiesFromJson(json: String): Map<String, Any>
    fun openUrl(url: String, useLaunchFlags: Boolean)
    fun startActivity(intent: Intent)

    /**
     * Determines if the view should be destroyed when detached from its parent.
     * The view should be destroyed if the activity is finishing or not changing configurations.
     * This is useful for managing resources and preventing memory leaks.
     */
    fun shouldDestroyViewOnDetach(): Boolean

    /**
     * Converts the given size from dp to pixels based on the device's screen density.
     */
    fun convertDpToPixels(size: Double): Int

    /**
     * Animates the size of the view to the specified width and height in pixels.
     * The animation duration can be specified, or a default value will be used.
     * Callbacks for animation start and end can also be provided.
     */
    @UiThread
    fun animateViewSize(
        widthInPx: Int? = null,
        heightInPx: Int? = null,
        duration: Long? = null,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null
    )
}

/**
 * Default implementation of [InAppPlatformDelegate] that delegates all calls to real Android
 * framework APIs.
 * The implementation is coupled to a specific [View] instance, which is used for context and
 * UI related operations.
 */
@InternalCustomerIOApi
class AndroidInAppPlatformDelegate(
    private val view: View
) : InAppPlatformDelegate {
    private val context: Context
        get() = view.context

    override fun parseJavaURI(uriString: String): URI {
        return URI(uriString)
    }

    override fun sanitizeUrlQuery(url: String): UrlQuerySanitizer {
        return UrlQuerySanitizer(url)
    }

    override fun parsePropertiesFromJson(json: String): Map<String, Any> {
        val parameterBinary = Base64.decode(json, Base64.DEFAULT)
        val parameterString = String(parameterBinary, StandardCharsets.UTF_8)
        val map: Map<String, Any> = HashMap()
        val properties = Gson().fromJson(parameterString, map.javaClass)
        return properties
    }

    override fun openUrl(url: String, useLaunchFlags: Boolean) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = url.toUri()
        if (useLaunchFlags) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        ContextCompat.startActivity(context, intent, null)
    }

    override fun startActivity(intent: Intent) {
        context.startActivity(intent)
    }

    override fun shouldDestroyViewOnDetach(): Boolean {
        val activity = view.context?.findActivity()
        return activity?.let {
            it.isFinishing || !it.isChangingConfigurations
        } ?: true
    }

    override fun convertDpToPixels(size: Double): Int {
        return size.toInt() * context.resources.displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT
    }

    @UiThread
    override fun animateViewSize(
        widthInPx: Int?,
        heightInPx: Int?,
        duration: Long?,
        onStart: (() -> Unit)?,
        onEnd: (() -> Unit)?
    ) {
        view.animateViewSize(
            widthInPx = widthInPx,
            heightInPx = heightInPx,
            duration = duration ?: view.resources.getInteger(
                android.R.integer.config_longAnimTime
            ).toLong(),
            onStart = onStart,
            onEnd = onEnd
        )
    }
}
