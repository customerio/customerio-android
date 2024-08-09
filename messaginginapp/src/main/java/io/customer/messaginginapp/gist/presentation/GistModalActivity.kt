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
import io.customer.messaginginapp.domain.InAppMessagingAction
import io.customer.messaginginapp.gist.data.model.GistMessageProperties
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.tracking.TrackableScreen

const val GIST_MESSAGE_INTENT: String = "GIST_MESSAGE"
const val GIST_MODAL_POSITION_INTENT: String = "GIST_MODAL_POSITION"

class GistModalActivity : AppCompatActivity(), GistListener, GistViewListener, TrackableScreen {
    private lateinit var binding: ActivityGistBinding
    private var currentMessage: Message? = null
    private var messagePosition: MessagePosition = MessagePosition.CENTER
    private var elapsedTimer: ElapsedTimer = ElapsedTimer()
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager

    // Flag to indicate if the message has been cancelled and should not perform any further actions
    private var isCancelled: Boolean = false

    // Indicates if the message is visible to user or not
    internal val isEngineVisible: Boolean
        get() = binding.gistView.isEngineVisible

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
        GistSdk.addListener(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        binding = ActivityGistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val messageStr = this.intent.getStringExtra(GIST_MESSAGE_INTENT)
        val modalPositionStr = this.intent.getStringExtra(GIST_MODAL_POSITION_INTENT)
        Gson().fromJson(messageStr, Message::class.java)?.let { messageObj ->
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity onCreate: $messageObj"))
            currentMessage = messageObj
            isCancelled = false
            currentMessage?.let { message ->
                elapsedTimer.start("Displaying modal for message: ${message.messageId}")
                binding.gistView.listener = this
                binding.gistView.setup(message)
                messagePosition = if (modalPositionStr == null) {
                    GistMessageProperties.getGistProperties(message).position
                } else {
                    MessagePosition.valueOf(modalPositionStr.uppercase())
                }
                when (messagePosition) {
                    MessagePosition.CENTER -> binding.modalGistViewLayout.setVerticalGravity(Gravity.CENTER_VERTICAL)
                    MessagePosition.BOTTOM -> binding.modalGistViewLayout.setVerticalGravity(Gravity.BOTTOM)
                    MessagePosition.TOP -> binding.modalGistViewLayout.setVerticalGravity(Gravity.TOP)
                }
            }
        } ?: run {
            finish()
        }

        // Update back button to handle in-app message behavior, disable back press for persistent messages, true otherwise
        val onBackPressedCallback = object : OnBackPressedCallback(isPersistentMessage()) {
            override fun handleOnBackPressed() {}
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    override fun onResume() {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity onResume"))
        super.onResume()
        GistSdk.addListener(this)
    }

    override fun onPause() {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity onPause"))
        GistSdk.removeListener(this)
        super.onPause()
        overridePendingTransition(0, 0)
    }

    override fun finish() {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity finish"))
        runOnUiThread {
            val animation = if (messagePosition == MessagePosition.TOP) {
                AnimatorInflater.loadAnimator(this, R.animator.animate_out_to_top)
            } else {
                AnimatorInflater.loadAnimator(this, R.animator.animate_out_to_bottom)
            }
            animation.setTarget(binding.modalGistViewLayout)
            animation.start()
            animation.doOnEnd {
                inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity finish animation completed"))
                super.finish()
            }
        }
    }

    override fun onDestroy() {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity onDestroy"))
        GistSdk.removeListener(this)
        // If the message has been cancelled, do not perform any further actions
        // to avoid sending any callbacks to the client app
        if (!isCancelled) {
            // If the message is not persistent, dismiss it and inform the callback
            if (!isPersistentMessage()) {
                GistSdk.dismissMessage()
            } else {
                GistSdk.clearCurrentMessage()
            }
        }
        GistSdk.gistModalManager.isMessageModalVisible = false
        super.onDestroy()
    }

    private fun isPersistentMessage(): Boolean = currentMessage?.let {
        GistMessageProperties.getGistProperties(
            it
        ).persistent
    } ?: false

    override fun onMessageShown(message: Message) {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity Message Shown: $message"))
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
                inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity Message Animation Completed: $message"))
                elapsedTimer.end()
            }
        }
    }

    override fun onMessageDismissed(message: Message) {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity Message Dismissed: $message"))
        currentMessage?.let { currentMessage ->
            if (currentMessage.instanceId == message.instanceId) {
                finish()
            }
        }
    }

    override fun onMessageCancelled(message: Message) {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("GisModelActivity Message Cancelled: $message"))
        currentMessage?.let { currentMessage ->
            if (currentMessage.instanceId == message.instanceId) {
                // Set the flag to indicate that the message has been cancelled
                isCancelled = true
                // Stop loading the message
                runOnUiThread {
                    binding.gistView.stopLoading()
                }
                // And finish the activity without performing any further actions
                super.finish()
            }
        }
    }

    override fun onGistViewSizeChanged(width: Int, height: Int) {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Size changed: $width x $height"))
        val params = binding.gistView.layoutParams
        params.height = height
        runOnUiThread {
            binding.modalGistViewLayout.updateViewLayout(binding.gistView, params)
        }
    }

    override fun onError(message: Message) {
        finish()
    }

    override fun embedMessage(message: Message, elementId: String) {}

    override fun onAction(message: Message, currentRoute: String, action: String, name: String) {}
}
