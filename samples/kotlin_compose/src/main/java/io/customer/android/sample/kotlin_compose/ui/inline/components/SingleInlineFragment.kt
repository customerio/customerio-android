package io.customer.android.sample.kotlin_compose.ui.inline.components

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.customer.android.sample.kotlin_compose.databinding.FragmentSingleInlineBinding

class SingleInlineFragment : Fragment() {
    private var _binding: FragmentSingleInlineBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSingleInlineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "SingleInlineFragment"

        @JvmStatic
        fun newInstance() = SingleInlineFragment()
    }
}
