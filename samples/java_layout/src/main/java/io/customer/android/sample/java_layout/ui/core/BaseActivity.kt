package io.customer.android.sample.java_layout.ui.core

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import io.customer.android.sample.java_layout.SampleApplication
import io.customer.android.sample.java_layout.di.ApplicationGraph

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB
    protected lateinit var applicationGraph: ApplicationGraph
    protected lateinit var viewModelProvider: ViewModelProvider

    protected abstract fun inflateViewBinding(): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge-to-edge display using Android's native helper
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        applicationGraph = (application as SampleApplication).applicationGraph
        binding = inflateViewBinding()
        setContentView(binding.root)

        // Apply window insets to handle system bars properly
        setupWindowInsets()

        viewModelProvider = ViewModelProvider(this, applicationGraph.viewModelFactory)
        readExtras()
        injectDependencies()
        setupContent()
    }

    /**
     * Handle window insets to ensure content doesn't get hidden behind system bars
     * and remains clickable. Uses Kotlin extension for cleaner code.
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply padding to avoid system bars
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )

            windowInsets
        }
    }

    protected open fun readExtras() {}

    protected open fun injectDependencies() {}

    protected open fun setupContent() {}
}
