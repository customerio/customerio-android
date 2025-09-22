package io.customer.android.sample.kotlin_compose.ui.inline.ui.main

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.customer.android.sample.kotlin_compose.ui.inline.components.MultipleInlineFragment
import io.customer.android.sample.kotlin_compose.ui.inline.components.SingleInlineFragment
import io.customer.android.sample.kotlin_compose.ui.inline.compose.KotlinComposeFragment
import io.customer.android.sample.kotlin_compose.ui.inline.recyclerview.RecyclerViewFragment

class SectionsPagerAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    override fun getItemCount(): Int {
        return 4
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> SingleInlineFragment.newInstance()
            1 -> MultipleInlineFragment.newInstance()
            2 -> KotlinComposeFragment.newInstance()
            3 -> RecyclerViewFragment.newInstance()
            else -> throw IllegalArgumentException("Invalid position")
        }
    }
}
