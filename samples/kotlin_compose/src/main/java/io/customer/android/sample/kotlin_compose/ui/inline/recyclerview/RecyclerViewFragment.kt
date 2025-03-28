package io.customer.android.sample.kotlin_compose.ui.inline.recyclerview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.customer.android.sample.kotlin_compose.databinding.FragmentRecyclerViewBinding
import io.customer.android.sample.kotlin_compose.ui.inline.InlineUtils
import io.customer.messaginginapp.gist.data.model.Message

class RecyclerViewFragment : Fragment() {
    private var _binding: FragmentRecyclerViewBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecyclerViewBinding.inflate(inflater, container, false)
        val root: View = binding.root
        setupContent()
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun logMessage(message: String) {
        InlineUtils.logMessage(tag = TAG, message = message)
    }

    private fun setupContent() {
        val recyclerViewAdapter = DynamicAdapter()
        binding.fetchMessagesButton.setOnClickListener {
            val inlineMessages = InlineUtils.getInlineMessages(TAG)
            val findAndSetMessage = { elementId: String, index: Int, action: (Message?) -> Unit ->
                val message = inlineMessages.firstOrNull { it.gistProperties.elementId == elementId }
                logMessage("$elementId: $message")
                action(message)
                recyclerViewAdapter.notifyItemChanged(index)
            }
            findAndSetMessage(InlineUtils.ELEMENT_ID_STICKY_HEADER, DynamicAdapter.FIRST_INLINE_INDEX) { recyclerViewAdapter.stickyHeaderInAppMessage = it }
            findAndSetMessage(InlineUtils.ELEMENT_ID_INLINE, DynamicAdapter.MIDDLE_INLINE_INDEX) { recyclerViewAdapter.inlineInAppMessage = it }
            findAndSetMessage(InlineUtils.ELEMENT_ID_BELOW_FOLD, DynamicAdapter.LAST_INLINE_INDEX) { recyclerViewAdapter.belowFoldInAppMessage = it }
        }
        binding.recyclerView.adapter = recyclerViewAdapter
    }

    companion object {
        private const val TAG = "RecyclerViewFragment"

        @JvmStatic
        fun newInstance() = RecyclerViewFragment()
    }
}
