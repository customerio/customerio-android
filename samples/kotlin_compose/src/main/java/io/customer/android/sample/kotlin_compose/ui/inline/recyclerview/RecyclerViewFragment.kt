package io.customer.android.sample.kotlin_compose.ui.inline.recyclerview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.customer.android.sample.kotlin_compose.databinding.FragmentRecyclerViewBinding

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

    private fun setupContent() {
        val recyclerViewAdapter = DynamicAdapter()
        binding.recyclerView.adapter = recyclerViewAdapter
    }

    companion object {
        private const val TAG = "RecyclerViewFragment"

        @JvmStatic
        fun newInstance() = RecyclerViewFragment()
    }
}
