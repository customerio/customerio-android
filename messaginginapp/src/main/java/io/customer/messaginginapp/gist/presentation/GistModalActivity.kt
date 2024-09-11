package io.customer.messaginginapp.gist.presentation

import android.animation.AnimatorInflater
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import com.google.gson.Gson
import io.customer.messaginginapp.R
import io.customer.messaginginapp.databinding.ActivityGistBinding
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.MessageState
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.tracking.TrackableScreen
import kotlinx.coroutines.Job

const val GIST_MESSAGE_INTENT: String = "GIST_MESSAGE"
const val GIST_MODAL_POSITION_INTENT: String = "GIST_MODAL_POSITION"

class GistModalActivity : AppCompatActivity(), GistViewListener, TrackableScreen {
    private lateinit var binding: ActivityGistBinding
    private var elapsedTimer: ElapsedTimer = ElapsedTimer()
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val logger = SDKComponent.logger
    private val attributesListenerJob: MutableList<Job> = mutableListOf()

    // indicates if the message is visible to user or not
    internal val isEngineVisible: Boolean
        get() = binding.gistView.isEngineVisible

    private var messagePosition: MessagePosition = MessagePosition.CENTER

    private val currentMessageState: MessageState.Displayed?
        get() = state.currentMessageState as? MessageState.Displayed

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
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        binding = ActivityGistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val messageStr = this.intent.getStringExtra(GIST_MESSAGE_INTENT)
        val modalPositionStr = this.intent.getStringExtra(GIST_MODAL_POSITION_INTENT)
        val parsedMessage = kotlin.runCatching { Gson().fromJson(messageStr, Message::class.java) }.getOrNull()
        if (parsedMessage == null) {
            logger.error("GistModelActivity onCreate: Message is null")
            finish()
        } else {
            logger.debug("GistModelActivity onCreate: $parsedMessage")
            parsedMessage.let { message ->
                elapsedTimer.start("Displaying modal for message: ${message.messageId}")
                binding.gistView.listener = this
                binding.gistView.setup(message)
                val messagePosition = if (modalPositionStr == null) {
                    message.gistProperties.position
                } else {
                    MessagePosition.valueOf(modalPositionStr.uppercase())
                }
                when (messagePosition) {
                    MessagePosition.CENTER -> binding.modalGistViewLayout.setVerticalGravity(Gravity.CENTER_VERTICAL)
                    MessagePosition.BOTTOM -> binding.modalGistViewLayout.setVerticalGravity(Gravity.BOTTOM)
                    MessagePosition.TOP -> binding.modalGistViewLayout.setVerticalGravity(Gravity.TOP)
                }
            }
        }

        subscribeToAttributes()

        // Update back button to handle in-app message behavior, disable back press for persistent messages, true otherwise
        val onBackPressedCallback = object : OnBackPressedCallback(isPersistentMessage(parsedMessage)) {
            override fun handleOnBackPressed() {}
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun subscribeToAttributes() {
        attributesListenerJob.add(
            inAppMessagingManager.subscribeToAttribute(
                selector = { it.currentMessageState },
                areEquivalent = { old, new ->
                    when {
                        old is MessageState.Initial && new is MessageState.Initial -> true
                        old is MessageState.Displayed && new is MessageState.Displayed -> old.message == new.message
                        old is MessageState.Dismissed && new is MessageState.Dismissed -> old.message == new.message
                        old is MessageState.Loading && new is MessageState.Loading -> old.message == new.message
                        else -> false
                    }
                }
            ) { state ->
                if (state is MessageState.Displayed) {
                    onMessageShown(state.message)
                }

                if (state is MessageState.Dismissed) {
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
        runOnUiThread {
            val animation = if (messagePosition == MessagePosition.TOP) {
                AnimatorInflater.loadAnimator(this, R.animator.animate_out_to_top)
            } else {
                AnimatorInflater.loadAnimator(this, R.animator.animate_out_to_bottom)
            }
            animation.setTarget(binding.modalGistViewLayout)
            animation.start()
            animation.doOnEnd {
                logger.debug("GistModelActivity finish animation completed")
                super.finish()
            }
        }
    }

    override fun onDestroy() {
        logger.debug("GistModelActivity onDestroy")
        attributesListenerJob.forEach(Job::cancel)
        // if the message has been cancelled, do not perform any further actions
        // to avoid sending any callbacks to the client app
        // If the message is not persistent, dismiss it and inform the callback

        currentMessageState?.let { state ->
            if (!isPersistentMessage()) {
                inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = state.message))
            } else {
                inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = state.message, shouldLog = false))
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
            val animation = if (messagePosition == MessagePosition.TOP) {
                AnimatorInflater.loadAnimator(this, R.animator.animate_in_from_top)
            } else {
                AnimatorInflater.loadAnimator(this, R.animator.animate_in_from_bottom)
            }
            animation.setTarget(binding.modalGistViewLayout)
            animation.start()
            animation.doOnEnd {
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
        super.finish()
    }

    override fun onGistViewSizeChanged(width: Int, height: Int) {
        logger.debug("GistModelActivity Size changed: $width x $height")
        val params = binding.gistView.layoutParams
        params.height = height
        runOnUiThread {
            binding.modalGistViewLayout.updateViewLayout(binding.gistView, params)
        }
    }
}
