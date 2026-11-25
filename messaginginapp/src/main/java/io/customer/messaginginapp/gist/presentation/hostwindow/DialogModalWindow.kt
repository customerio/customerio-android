package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.core.animation.doOnEnd
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import io.customer.messaginginapp.R
import io.customer.messaginginapp.databinding.ActivityGistBinding
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.utilities.MessageOverlayColorParser
import io.customer.messaginginapp.gist.utilities.ModalAnimationUtil
import io.customer.messaginginapp.ui.bridge.ModalInAppMessageViewCallback
import io.customer.sdk.core.util.Logger

/**
 * Renders a modal in a full-screen dialog window attached to the host activity.
 *
 * A dialog is a window of the host's own task, so it works for a `singleInstance` host. It also gives
 * window-level back handling, focus and accessibility modality without hand-rolled code. Firebase In-App
 * Messaging (an attached dialog window) and OneSignal (a PopupWindow) use a separate window the same way.
 * The content is the layout GistModalActivity uses.
 */
internal class DialogModalWindow(
    override val host: Activity,
    private val message: Message,
    private val position: MessagePosition,
    private val shouldDispatchDisplayEvent: Boolean,
    private val listener: ModalWindowListener,
    private val logger: Logger,
    /** Runs a block on the main thread. Injectable so tests can observe the hand-off. */
    private val postToMain: (() -> Unit) -> Unit = { block -> host.runOnUiThread(block) }
) : ModalWindow, ModalInAppMessageViewCallback {

    private val dialog = ComponentDialog(host, R.style.GistHostWindowDialogTheme)
    private val binding = ActivityGistBinding.inflate(LayoutInflater.from(dialog.context))
    private var released = false
    private var shown = false

    /** Set once the renderer was stopped. Separate from [released], which the dialog's own dismissal also sets. */
    private var rendererCleanedUp = false

    /**
     * The lifecycle the renderer observes. The dialog's own lifecycle stays resumed for as long as the
     * dialog is shown, even while the host is paused, so the renderer would keep its bridge and load
     * timeout running in the background. This one follows the host through [onHostPaused] and
     * [onHostResumed], like GistModalActivity's lifecycle does for the activity path.
     */
    private val rendererLifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    override val isReleased: Boolean
        get() = released

    init {
        configureWindow()
        dialog.setContentView(binding.root)
        // After setContentView, which points the view tree at the dialog's own lifecycle.
        dialog.window?.decorView?.setViewTreeLifecycleOwner(rendererLifecycleOwner)
        rendererLifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        applySystemBarInsets()

        val persistent = message.gistProperties.persistent
        // Like the activity, a persistent message swallows back presses instead of closing.
        dialog.setCancelable(!persistent)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { listener.onCancelled() }
        // Every way the dialog goes away (back, the system, release) ends here, so the renderer is
        // always stopped. Otherwise its color-scheme subscription keeps the view and the host alive.
        dialog.setOnDismissListener {
            released = true
            cleanUpRenderer()
        }

        binding.gistView.setViewCallback(this)
    }

    /**
     * Called from the web view's JavaScript bridge thread, so the layout update hops to the main
     * thread, as GistModalActivity does. Touching the views from the bridge thread throws
     * CalledFromWrongThreadException, loses the resize and leaves the view full height.
     */
    override fun onViewSizeChanged(width: Int, height: Int) {
        logger.debug("HostWindowModalWindow size changed: $width x $height")
        postToMain {
            if (released) return@postToMain
            val params = binding.gistView.layoutParams
            params.height = height
            binding.modalGistViewLayout.updateViewLayout(binding.gistView, params)
        }
    }

    override fun show() {
        if (released || host.isFinishing || host.isDestroyed) {
            released = true
            cleanUpRenderer()
            return
        }
        // Windows are only created for the resumed host, so the renderer starts resumed.
        rendererLifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        binding.gistView.setup(message, shouldDispatchDisplayEvent)
        applyPosition()
        dialog.show()
        shown = true
    }

    override fun onMessageDisplayed(animate: Boolean) {
        if (released) return
        // The window blocked touches and focus while loading, like the activity's FLAG_NOT_TOUCHABLE.
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val layout = binding.modalGistViewLayout
        layout.visibility = View.VISIBLE

        val overlayColor = MessageOverlayColorParser.parseColor(message.gistProperties.overlayColor)
            ?: ModalAnimationUtil.FALLBACK_COLOR_STRING
        if (animate) {
            val animator = if (position == MessagePosition.TOP) {
                ModalAnimationUtil.createAnimationSetInFromTop(layout, overlayColor)
            } else {
                ModalAnimationUtil.createAnimationSetInFromBottom(layout, overlayColor)
            }
            animator.start()
        } else {
            layout.alpha = 1f
            layout.translationY = 0f
            layout.setBackgroundColor(parseColor(overlayColor))
        }
    }

    override fun dismiss(onDismissed: () -> Unit) {
        if (released || !shown || binding.modalGistViewLayout.visibility != View.VISIBLE) {
            release()
            onDismissed()
            return
        }
        // As in GistModalActivity's cleanUp, the renderer stops before the exit animation. The next
        // message can already be loading underneath, and a second close tap or Back on this one would
        // dismiss it again, which replaces the store's modal state and ends the next message.
        cleanUpRenderer()
        dialog.setCancelable(false)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val layout = binding.modalGistViewLayout
        val animator = if (position == MessagePosition.TOP) {
            ModalAnimationUtil.createAnimationSetOutToTop(layout)
        } else {
            ModalAnimationUtil.createAnimationSetOutToBottom(layout)
        }
        animator.doOnEnd {
            release()
            onDismissed()
        }
        animator.start()
    }

    override fun release() {
        released = true
        cleanUpRenderer()
        runCatching { if (dialog.isShowing) dialog.dismiss() }
    }

    /**
     * Drops the renderer to CREATED, which sends it ON_PAUSE. STARTED would not hold: the engine
     * resumes itself when its view attaches under a STARTED owner (EngineWebView.onAttachedToWindow),
     * and on a busy main thread the dialog's first attach can land after Home, before the host's stop.
     */
    override fun onHostPaused() {
        if (rendererCleanedUp) return
        if (rendererLifecycleOwner.registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            rendererLifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
    }

    override fun onHostResumed() {
        if (rendererCleanedUp || !shown) return
        rendererLifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
    }

    /** Stops the renderer once, whichever dismissal path gets here first. */
    private fun cleanUpRenderer() {
        if (rendererCleanedUp) return
        rendererCleanedUp = true
        runCatching { binding.gistView.stopLoading() }
        rendererLifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
    }

    private fun configureWindow() {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setWindowAnimations(0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Invisible and inert until the message is displayed.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Draw behind the system bars so the overlay covers the whole screen, as the activity does.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep Android from painting contrast scrims behind the bars.
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // This window controls the bar icons from the moment it attaches, and the dialog theme is dark, which would
        // turn them light. Match the activity's enableEdgeToEdge defaults instead: status bar icons follow night mode,
        // navigation bar icons stay light.
        val isNightMode = (window.decorView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    // Pad the overlay layout so the message clears the bars, keyboard and cutout while the scrim fills the screen.
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.modalGistViewLayout) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun applyPosition() {
        when (position) {
            MessagePosition.CENTER -> binding.modalGistViewLayout.setVerticalGravity(Gravity.CENTER_VERTICAL)
            MessagePosition.BOTTOM -> binding.modalGistViewLayout.setVerticalGravity(Gravity.BOTTOM)
            MessagePosition.TOP -> binding.modalGistViewLayout.setVerticalGravity(Gravity.TOP)
        }
    }

    private fun parseColor(color: String): Int {
        return runCatching { color.toColorInt() }
            .getOrElse { ModalAnimationUtil.FALLBACK_COLOR_STRING.toColorInt() }
    }
}

internal class DialogModalWindowFactory(private val logger: Logger) : ModalWindowFactory {
    override fun create(
        host: Activity,
        message: Message,
        position: MessagePosition,
        shouldDispatchDisplayEvent: Boolean,
        listener: ModalWindowListener
    ): ModalWindow = DialogModalWindow(host, message, position, shouldDispatchDisplayEvent, listener, logger)
}
