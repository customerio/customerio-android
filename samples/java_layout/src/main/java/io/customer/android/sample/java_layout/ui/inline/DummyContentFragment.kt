package io.customer.android.sample.java_layout.ui.inline

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.customer.android.sample.java_layout.R

class DummyContentFragment : Fragment() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("DummyContentFragment", "onCreate() called - Fragment created")
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d("DummyContentFragment", "onCreateView() called - View created")
        return inflater.inflate(R.layout.fragment_dummy_content, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("DummyContentFragment", "onViewCreated() called - View setup complete")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d("DummyContentFragment", "onDestroy() called - Fragment destroyed")
    }
    
    companion object {
        @JvmStatic
        fun newInstance(): DummyContentFragment {
            return DummyContentFragment()
        }
    }
}