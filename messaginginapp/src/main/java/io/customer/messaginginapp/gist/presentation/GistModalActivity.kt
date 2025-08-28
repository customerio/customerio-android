package io.customer.messaginginapp.gist.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.lifecycle.lifecycleScope
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.databinding.ActivityGistBinding
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.modalMessageParser
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import io.customer.messaginginapp.gist.utilities.MessageOverlayColorParser
import io.customer.messaginginapp.gist.utilities.ModalAnimationUtil
import io.customer.messaginginapp.gist.utilities.ModalMessageExtras
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.ModalMessageState
import io.customer.messaginginapp.ui.bridge.ModalInAppMessageViewCallback
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.tracking.TrackableScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@InternalCustomerIOApi
class GistModalActivity : AppCompatActivity(), ModalInAppMessageViewCallback, TrackableScreen {
    private lateinit var binding: ActivityGistBinding
    private var messagePosition: MessagePosition = MessagePosition.CENTER

    private val attributesListenerJob: MutableList<Job> = mutableListOf()
    private val elapsedTimer: ElapsedTimer = ElapsedTimer()
    private val logger = SDKComponent.logger
    private val dispatchersProvider = SDKComponent.dispatchersProvider
    private val modalMessageParser = SDKComponent.modalMessageParser

    // Dependencies requiring ModuleMessagingInApp to be initialized must be accessed lazily,
    // only after confirming the SDK has been initialized.
    private val inAppMessagingManager: InAppMessagingManager?
        get() = kotlin.runCatching {
            SDKComponent.inAppMessagingManager
        }.getOrNull()
    private val state: InAppMessagingState?
        get() = inAppMessagingManager?.getCurrentState()
    private val currentMessageState: ModalMessageState.Displayed?
        get() = state?.modalMessageState as? ModalMessageState.Displayed

    override fun getScreenName(): String? {
        // Return null to prevent this screen from being tracked
        return null
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, GistModalActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareActivity()
    }

    private fun prepareActivity() = lifecycleScope.launch(dispatchersProvider.main) {
        val result = validateAndParseIntentExtras()

        if (result == null) {
            // Finish the activity immediately to avoid running animations or further processing
            finishImmediately()
            return@launch
        }

        val (message, modalPosition) = result
        messagePosition = modalPosition

        initializeActivity()
        setupMessage(message)
        subscribeToAttributes()
        setupBackPressedCallback(message)
    }

    private suspend fun validateAndParseIntentExtras(): ModalMessageExtras? {
        // Check if the SDK is initialized before parsing the intent
        if (inAppMessagingManager == null) {
            logger.error("GistModalActivity onCreate: ModuleMessagingInApp not initialized")
            return null
        }

        return modalMessageParser.parseExtras(intent = intent)
    }

    private fun initializeActivity() {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        binding = ActivityGistBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    private fun setupMessage(message: Message) {
        logger.debug("GistModelActivity onCreate: $message")
        elapsedTimer.start("Displaying modal for message: ${message.messageId}")

        // Configure GistView
        binding.gistView.setViewCallback(this)
        binding.gistView.setup(message)

        // Apply pre-parsed message position
        when (messagePosition) {
            MessagePosition.CENTER -> binding.modalGistViewLayout.setVerticalGravity(Gravity.CENTER_VERTICAL)
            MessagePosition.BOTTOM -> binding.modalGistViewLayout.setVerticalGravity(Gravity.BOTTOM)
            MessagePosition.TOP -> binding.modalGistViewLayout.setVerticalGravity(Gravity.TOP)
        }
    }

    private fun setupBackPressedCallback(message: Message?) {
        val onBackPressedCallback = object : OnBackPressedCallback(isPersistentMessage(message)) {
            override fun handleOnBackPressed() {}
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun subscribeToAttributes() {
        val inAppManager = inAppMessagingManager ?: return

        attributesListenerJob.add(
            inAppManager.subscribeToAttribute(
                selector = { it.modalMessageState },
                areEquivalent = { old, new ->
                    when {
                        old is ModalMessageState.Initial && new is ModalMessageState.Initial -> true
                        old is ModalMessageState.Displayed && new is ModalMessageState.Displayed -> old.message == new.message
                        old is ModalMessageState.Dismissed && new is ModalMessageState.Dismissed -> old.message == new.message
                        old is ModalMessageState.Loading && new is ModalMessageState.Loading -> old.message == new.message
                        else -> false
                    }
                }
            ) { state ->
                if (state is ModalMessageState.Displayed) {
                    onMessageShown(state.message)
                }

                if (state is ModalMessageState.Dismissed) {
                    cleanUp()
                }
            }
        )
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    override fun finish() {
        logger.debug("GistModelActivity finish")
        // Prevent crash if finish() called before binding initialization (e.g., from back press during onCreate)
        if (!::binding.isInitialized) {
            logger.debug("GistModelActivity finish: binding not initialized, finishing immediately")
            finishImmediately()
            return
        }

        runOnUiThread {
            val animationSet = if (messagePosition == MessagePosition.TOP) {
                ModalAnimationUtil.createAnimationSetOutToTop(binding.modalGistViewLayout)
            } else {
                ModalAnimationUtil.createAnimationSetOutToBottom(binding.modalGistViewLayout)
            }
            animationSet.start()
            animationSet.doOnEnd {
                logger.debug("GistModelActivity finish animation completed")
                finishImmediately()
            }
        }
    }

    // Completes finish process by calling super and handling cleanup without further actions
    private fun finishImmediately() {
        super.finish()
    }

    override fun onDestroy() {
        logger.debug("GistModelActivity onDestroy")
        for (job in attributesListenerJob) {
            job.cancel()
        }
        // if the message has been cancelled, do not perform any further actions
        // to avoid sending any callbacks to the client app
        // If the message is not persistent, dismiss it and inform the callback

        val state = currentMessageState
        val inAppManager = inAppMessagingManager
        if (state != null && inAppManager != null) {
            if (!isPersistentMessage()) {
                inAppManager.dispatch(InAppMessagingAction.DismissMessage(message = state.message))
            } else {
                inAppManager.dispatch(InAppMessagingAction.DismissMessage(message = state.message, shouldLog = false))
            }
        }
        super.onDestroy()
    }

    private fun isPersistentMessage(message: Message? = null): Boolean {
        val currentMessage = message ?: currentMessageState?.message
        return currentMessage?.gistProperties?.persistent ?: false
    }

    private fun onMessageShown(message: Message) {
        logger.debug("GistModelActivity Message Shown: $message")
        runOnUiThread {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            binding.modalGistViewLayout.visibility = View.VISIBLE

            val overlayColor = MessageOverlayColorParser.parseColor(message.gistProperties.overlayColor)
                ?: ModalAnimationUtil.FALLBACK_COLOR_STRING
            val animatorSet = if (messagePosition == MessagePosition.TOP) {
                ModalAnimationUtil.createAnimationSetInFromTop(binding.modalGistViewLayout, overlayColor)
            } else {
                ModalAnimationUtil.createAnimationSetInFromBottom(binding.modalGistViewLayout, overlayColor)
            }
            animatorSet.start()
            animatorSet.doOnEnd {
                logger.debug("GistModelActivity Message Animation Completed: $message")
                elapsedTimer.end()
            }
        }
    }

    private fun cleanUp() {
        // stop loading the message
        runOnUiThread {
            binding.gistView.stopLoading()
        }
        // and finish the activity without performing any further actions
        finish()
    }

    override fun onViewSizeChanged(width: Int, height: Int) {
        logger.debug("GistModelActivity Size changed: $width x $height")
        val params = binding.gistView.layoutParams
        params.height = height
        runOnUiThread {
            binding.modalGistViewLayout.updateViewLayout(binding.gistView, params)
        }
    }
}
