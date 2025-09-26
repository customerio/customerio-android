package io.customer.android.sample.java_layout.ui.inline

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import io.customer.android.sample.java_layout.R
import io.customer.android.sample.java_layout.databinding.ActivityInlineMessagesTabbedBinding
import io.customer.sdk.tracking.TrackableScreen

class InlineExamplesActivity : AppCompatActivity(), TrackableScreen {
    private lateinit var binding: ActivityInlineMessagesTabbedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInlineMessagesTabbedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupContent()
    }

    override fun getScreenName(): String = getString(R.string.label_inline_examples_activity)

    private fun setupContent() {
        // Setup toolbar
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getScreenName()

        // Setup ViewPager with tabs
        val sectionsPagerAdapter = SectionsPagerAdapter(supportFragmentManager, lifecycle)
        binding.viewPager.adapter = sectionsPagerAdapter
        // Keep all fragments in memory to prevent reload/reanimate on tab switch
        // Since we only have 4 tabs, this is acceptable for memory usage
        binding.viewPager.offscreenPageLimit = 4

        // Setup tab layout with ViewPager
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            val iconResId: Int
            val textResId: Int

            when (position) {
                0 -> {
                    iconResId = R.drawable.ic_single_black_24dp
                    textResId = R.string.title_inline_single
                }
                1 -> {
                    iconResId = R.drawable.ic_multiple_black_24dp
                    textResId = R.string.title_inline_multiple
                }
                2 -> {
                    iconResId = R.drawable.ic_compose_black_24dp
                    textResId = R.string.title_inline_compose
                }
                3 -> {
                    iconResId = R.drawable.ic_recycler_black_24dp
                    textResId = R.string.title_inline_recycler
                }
                else -> throw IllegalArgumentException("Invalid position: $position")
            }

            tab.setIcon(iconResId)
            tab.setText(textResId)
        }.attach()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
