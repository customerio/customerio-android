package io.customer.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat

import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleRobolectricTest {

    val context: Context
        get() = getApplicationContext<MainApplication>()

    @Test
    fun canAccessR() {
        assertThat(context.getString(R.string.app_name)).isEqualTo("Customer.io SDK Example")
    }

}