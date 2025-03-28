package io.customer.android.sample.kotlin_compose.ui.inline

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.databinding.ActivityInlineMessagesNavigationBinding

class InlineMessagesNavigationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInlineMessagesNavigationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInlineMessagesNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupContent()
    }

    private fun setupContent() {
        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_multiple_inline_messages)
        navView.setupWithNavController(navController)
    }
}
