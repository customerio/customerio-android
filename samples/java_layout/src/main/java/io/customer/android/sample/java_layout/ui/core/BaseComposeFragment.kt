package io.customer.android.sample.java_layout.ui.core

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import io.customer.android.sample.java_layout.SampleApplication
import io.customer.android.sample.java_layout.di.ApplicationGraph

/**
 * Base fragment for Compose-based fragments.
 * Similar to BaseFragment but designed for Compose UI instead of view binding.
 */
abstract class BaseComposeFragment : Fragment() {

    protected lateinit var applicationGraph: ApplicationGraph
    protected lateinit var viewModelProvider: ViewModelProvider

    /**
     * Abstract method that creates the Compose view
     */
    protected abstract fun createComposeView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize application graph
        applicationGraph = (requireActivity().application as SampleApplication).applicationGraph
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Initialize the ViewModel provider
        viewModelProvider = ViewModelProvider(this, applicationGraph.viewModelFactory)
        
        // Call injectDependencies before creating the view
        injectDependencies()
        
        // Create the compose view
        val view = createComposeView(inflater, container, savedInstanceState)
        
        // Setup any other content
        setupContent()
        
        return view
    }

    /**
     * Override this method to inject dependencies
     */
    protected open fun injectDependencies() {
        // Default empty implementation
    }

    /**
     * Override this method to setup additional content if needed
     */
    protected open fun setupContent() {
        // Default empty implementation
    }
}