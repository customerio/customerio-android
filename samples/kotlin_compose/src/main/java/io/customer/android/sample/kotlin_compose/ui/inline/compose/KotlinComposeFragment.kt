package io.customer.android.sample.kotlin_compose.ui.inline.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import io.customer.android.sample.kotlin_compose.ui.inline.InlineUtils
import io.customer.messaginginapp.gist.data.model.Message

class KotlinComposeFragment : Fragment() {
    private var stickHeaderMessageSaved: Message? = null
    private var inlineMessageSaved: Message? = null
    private var belowFoldMessageSaved: Message? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                var stickHeaderMessage by remember { mutableStateOf(stickHeaderMessageSaved) }
                var inlineMessage by remember { mutableStateOf(inlineMessageSaved) }
                var belowFoldMessage by remember { mutableStateOf(belowFoldMessageSaved) }

                KotlinComposeInlineComponent(
                    stickHeaderMessage = stickHeaderMessage,
                    inlineMessage = inlineMessage,
                    belowFoldMessage = belowFoldMessage,
                    onFetchMessagesClick = {
                        val inlineMessages = InlineUtils.getInlineMessages(TAG)
                        val findMessage = { elementId: String ->
                            inlineMessages.firstOrNull {
                                it.gistProperties.elementId == elementId
                            }.also { message ->
                                logMessage("$elementId: $message")
                            }
                        }
                        findMessage(InlineUtils.ELEMENT_ID_STICKY_HEADER).let {
                            stickHeaderMessage = it
                            stickHeaderMessageSaved = it
                        }
                        findMessage(InlineUtils.ELEMENT_ID_INLINE).let {
                            inlineMessage = it
                            inlineMessageSaved = it
                        }
                        findMessage(InlineUtils.ELEMENT_ID_BELOW_FOLD).let {
                            belowFoldMessage = it
                            belowFoldMessageSaved = it
                        }
                    }
                )
            }
        }
    }

    private fun logMessage(message: String) {
        InlineUtils.logMessage(tag = TAG, message = message)
    }

    companion object {
        private const val TAG = "KotlinComposeFragment"

        @JvmStatic
        fun newInstance() = KotlinComposeFragment()
    }
}
