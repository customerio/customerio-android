package io.customer.android.sample.kotlin_compose.ui.inline

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.databinding.ActivityInlineMessagesTabbedBinding
import io.customer.android.sample.kotlin_compose.ui.inline.ui.main.SectionsPagerAdapter

class InlineMessagesTabbedActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInlineMessagesTabbedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInlineMessagesTabbedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupContent()
    }

    private fun setupContent() {
        val sectionsPagerAdapter = SectionsPagerAdapter(supportFragmentManager, lifecycle)
        binding.viewPager.adapter = sectionsPagerAdapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            val (iconResId, textResId) = when (position) {
                0 -> R.drawable.ic_single_black_24dp to R.string.title_inline_single
                1 -> R.drawable.ic_multiple_black_24dp to R.string.title_inline_multiple
                2 -> R.drawable.ic_compose_black_24dp to R.string.title_inline_compose
                3 -> R.drawable.ic_recycler_black_24dp to R.string.title_inline_recycler
                else -> throw IllegalArgumentException("Invalid position")
            }
            tab.setIcon(iconResId)
            tab.setText(textResId)
        }.attach()
    }
}
