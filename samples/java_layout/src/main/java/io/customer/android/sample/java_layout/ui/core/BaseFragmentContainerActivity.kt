package io.customer.android.sample.java_layout.ui.core

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.google.android.material.progressindicator.BaseProgressIndicator
import io.customer.android.sample.java_layout.ui.dashboard.DashboardActivity
import io.customer.android.sample.java_layout.ui.login.LoginActivity
import io.customer.android.sample.java_layout.ui.user.AuthViewModel
import io.customer.android.sample.java_layout.utils.ViewUtils

abstract class BaseFragmentContainerActivity<VB : ViewBinding> : BaseActivity<VB>() {
    protected abstract val fragmentContainer: View
    protected abstract fun findFragmentByName(fragmentName: String): Fragment?

    protected open val progressIndicator: BaseProgressIndicator<*>? = null
    protected open fun getFragmentTitle(): String? = null

    protected lateinit var fragmentName: String
    protected var hasDisplayedContent = false

    override fun onBackPressed() {
        finish()
    }

    private fun navigateUp() {
        // For better user experience, navigate to launcher activity on navigate up button
        if (isTaskRoot) {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        onBackPressed()
    }

    override fun readExtras() {
        fragmentName = intent?.extras?.getString(ARG_FRAGMENT_NAME)?.takeIf {
            it.isNotBlank()
        } ?: throw IllegalArgumentException("Fragment name cannot be null")
    }

    protected fun setupToolbar(toolbar: Toolbar, useAsSupportActionBar: Boolean = false) {
        ViewUtils.prepareForAutomatedTests(toolbar)
        if (useAsSupportActionBar) {
            setSupportActionBar(toolbar)
        }
        toolbar.setNavigationOnClickListener { _ -> navigateUp() }
    }

    protected fun setupWithAuthViewModel(authViewModel: AuthViewModel) {
        authViewModel.userLoggedInStateObservable.observe(this) { isLoggedIn: Boolean ->
            if (isLoggedIn) {
                // LiveData can emit the same value again when Activity returns from background.
                // To avoid replacing the fragment multiple times, we track whether the fragment has already
                // been added using a boolean flag.
                if (!hasDisplayedContent) {
                    replaceFragmentInView()
                    hasDisplayedContent = true
                }
            } else {
                if (isTaskRoot) {
                    startActivity(Intent(this, LoginActivity::class.java))
                }
                finish()
            }
        }
    }

    private fun replaceFragmentInView() {
        val fragment = findFragmentByName(fragmentName) ?: throw IllegalArgumentException("Invalid fragment name provided")
        val fragmentManager = supportFragmentManager
        val fragmentTransaction = fragmentManager.beginTransaction()
        fragmentTransaction.replace(fragmentContainer.id, fragment)
        fragmentTransaction.commit()
        getFragmentTitle()?.let(::setTitle)
        progressIndicator?.hide()
    }

    companion object {
        protected const val ARG_FRAGMENT_NAME = "fragment_name"

        @JvmStatic
        protected fun getExtras(fragmentName: String?): Bundle {
            val extras = Bundle()
            extras.putString(ARG_FRAGMENT_NAME, fragmentName)
            return extras
        }
    }
}
