package io.customer.android.sample.java_layout.ui.inline

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import io.customer.android.sample.java_layout.R
import io.customer.android.sample.java_layout.databinding.ActivityInlineExamplesWithTabsBinding
import io.customer.android.sample.java_layout.ui.core.BaseActivity
import io.customer.android.sample.java_layout.ui.settings.SettingsActivity
import io.customer.android.sample.java_layout.ui.user.AuthViewModel
import io.customer.sdk.tracking.TrackableScreen

class InlineExamplesActivity :
    BaseActivity<ActivityInlineExamplesWithTabsBinding>(),
    TrackableScreen {
    private lateinit var authViewModel: AuthViewModel
    private lateinit var fragmentName: String
    
    // Fragment cache to ensure instances are preserved
    private var inlineExamplesFragment: InlineExamplesContainerFragment? = null
    private var dummyContentFragment: DummyContentFragment? = null
    private var currentTabPosition: Int = -1 // Initialize to -1 so initial switchToTab(0) works

    override fun getScreenName(): String = getString(R.string.label_inline_examples_activity)

    override fun inflateViewBinding(): ActivityInlineExamplesWithTabsBinding {
        return ActivityInlineExamplesWithTabsBinding.inflate(layoutInflater)
    }

    override fun readExtras() {
        fragmentName = intent?.extras?.getString(ARG_FRAGMENT_NAME)?.takeIf {
            it.isNotBlank()
        } ?: FRAGMENT_ANDROID_XML // Default to Android XML if not specified
    }

    override fun injectDependencies() {
        authViewModel = viewModelProvider[AuthViewModel::class.java]
    }

    override fun setupContent() {
        setupToolbar(binding.topAppBar, useAsSupportActionBar = true)
        setupTabs()
        setupWithAuthViewModel(authViewModel)
    }

    private fun setupToolbar(toolbar: androidx.appcompat.widget.Toolbar, useAsSupportActionBar: Boolean = false) {
        if (useAsSupportActionBar) {
            setSupportActionBar(toolbar)
        }
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun setupTabs() {
        // Setup tab titles
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            text = when (fragmentName) {
                FRAGMENT_ANDROID_XML -> getString(R.string.inline_examples_xml_layout)
                FRAGMENT_COMPOSE -> getString(R.string.inline_examples_compose)
                else -> "Inline Examples"
            }
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            text = "Dummy Content"
        })
        
        // Setup tab click listener
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.position?.let { position ->
                    Log.d("InlineExamplesActivity", "Tab selected: $position")
                    switchToTab(position)
                }
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {
                // No action needed
            }
            
            override fun onTabReselected(tab: TabLayout.Tab?) {
                // No action needed
            }
        })
        
        // Show the first tab initially
        switchToTab(0)
    }
    
    private fun switchToTab(position: Int) {
        if (currentTabPosition == position) {
            return // Already showing this tab
        }
        
        Log.d("InlineExamplesActivity", "Switching to tab: $position")
        currentTabPosition = position
        
        val fragment = getFragmentForPosition(position)
        
        supportFragmentManager.beginTransaction().apply {
            replace(R.id.fragment_container, fragment)
            commit()
        }
    }
    
    private fun getFragmentForPosition(position: Int): Fragment {
        return when (position) {
            0 -> {
                // Create and cache the inline examples fragment if not already created
                if (inlineExamplesFragment == null) {
                    Log.d("InlineExamplesActivity", "Creating new InlineExamplesContainerFragment")
                    inlineExamplesFragment = InlineExamplesContainerFragment.newInstance(fragmentName)
                } else {
                    Log.d("InlineExamplesActivity", "Reusing existing InlineExamplesContainerFragment")
                }
                inlineExamplesFragment!!
            }
            1 -> {
                // Create and cache the dummy fragment if not already created
                if (dummyContentFragment == null) {
                    Log.d("InlineExamplesActivity", "Creating new DummyContentFragment")
                    dummyContentFragment = DummyContentFragment.newInstance()
                } else {
                    Log.d("InlineExamplesActivity", "Reusing existing DummyContentFragment")
                }
                dummyContentFragment!!
            }
            else -> throw IllegalArgumentException("Invalid tab position: $position")
        }
    }

    private fun setupWithAuthViewModel(authViewModel: AuthViewModel) {
        // Check if user is logged in (simplified version)
        // In real app, observe LiveData here
        binding.progressIndicator.hide()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_inline_examples, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val ARG_FRAGMENT_NAME = "fragment_name"
        const val FRAGMENT_ANDROID_XML: String = "FRAGMENT_ANDROID_XML"
        const val FRAGMENT_COMPOSE: String = "FRAGMENT_COMPOSE"

        @JvmStatic
        fun getExtras(fragmentName: String?): Bundle {
            val extras = Bundle()
            extras.putString(ARG_FRAGMENT_NAME, fragmentName)
            return extras
        }
    }
}
