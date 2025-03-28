package io.customer.android.sample.kotlin_compose.ui.inline.components

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.customer.android.sample.kotlin_compose.databinding.FragmentSingleInlineBinding
import io.customer.android.sample.kotlin_compose.ui.inline.InlineUtils
import io.customer.messaginginapp.inline.InlineMessageBaseView

class SingleInlineFragment : Fragment() {
    private var _binding: FragmentSingleInlineBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSingleInlineBinding.inflate(inflater, container, false)
        val root: View = binding.root
        setupContent()
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupContent() {
        binding.fetchMessagesButton.setOnClickListener {
            val inlineMessages = InlineUtils.getInlineMessages(TAG)
            val findAndShowMessage = { view: InlineMessageBaseView, elementId: String ->
                val message = inlineMessages.firstOrNull { it.gistProperties.elementId == elementId }
                logMessage("$elementId: $message")
                message?.let { view.showMessage(it) }
            }
            findAndShowMessage(binding.inappMessage, InlineUtils.ELEMENT_ID_STICKY_HEADER)
        }
    }

    private fun logMessage(message: String) {
        InlineUtils.logMessage(tag = TAG, message = message)
    }

    companion object {
        private const val TAG = "SingleInlineFragment"

        @JvmStatic
        fun newInstance() = SingleInlineFragment()
    }
}
