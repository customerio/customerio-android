package io.customer.android.sample.java_layout.ui.inline

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.customer.android.sample.java_layout.R
import io.customer.android.sample.java_layout.ui.inline.AndroidXMLInlineExampleFragment
import io.customer.android.sample.java_layout.ui.inline.compose.ComposeInlineExampleFragment

class InlineExamplesContainerFragment : Fragment() {
    
    private var fragmentName: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentName = arguments?.getString(ARG_FRAGMENT_NAME)
        Log.d("InlineExamplesContainerFragment", "onCreate() called - Fragment created for: $fragmentName")
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d("InlineExamplesContainerFragment", "onCreateView() called - View created for: $fragmentName")
        return inflater.inflate(R.layout.fragment_inline_examples_container, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("InlineExamplesContainerFragment", "onViewCreated() called - Setting up child fragment for: $fragmentName")
        
        // Load the appropriate fragment based on the fragment name
        val childFragment = findFragmentByName(fragmentName)
        if (childFragment != null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.container, childFragment)
                .commit()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d("InlineExamplesContainerFragment", "onDestroy() called - Fragment destroyed for: $fragmentName")
    }
    
    private fun findFragmentByName(fragmentName: String?): Fragment? = when (fragmentName) {
        FRAGMENT_ANDROID_XML -> AndroidXMLInlineExampleFragment.newInstance()
        FRAGMENT_COMPOSE -> ComposeInlineExampleFragment.newInstance()
        else -> null
    }
    
    companion object {
        private const val ARG_FRAGMENT_NAME = "fragment_name"
        const val FRAGMENT_ANDROID_XML: String = "FRAGMENT_ANDROID_XML"
        const val FRAGMENT_COMPOSE: String = "FRAGMENT_COMPOSE"
        
        @JvmStatic
        fun newInstance(fragmentName: String): InlineExamplesContainerFragment {
            val fragment = InlineExamplesContainerFragment()
            val args = Bundle()
            args.putString(ARG_FRAGMENT_NAME, fragmentName)
            fragment.arguments = args
            return fragment
        }
    }
}