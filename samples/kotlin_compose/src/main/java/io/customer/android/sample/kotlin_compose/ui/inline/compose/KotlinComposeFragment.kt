package io.customer.android.sample.kotlin_compose.ui.inline.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment

class KotlinComposeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                KotlinComposeInlineComponent(
                    context = requireContext()
                )
            }
        }
    }

    companion object {
        private const val TAG = "KotlinComposeFragment"

        @JvmStatic
        fun newInstance() = KotlinComposeFragment()
    }
}
