package io.customer.example

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import io.customer.base.data.ErrorResult
import io.customer.base.data.Success
import io.customer.sdk.CustomerIo
import io.customer.sdk.data.model.IdentityAttributeValue

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CustomerIo.instance()
            .identify(
                "188",
                attributes = mapOf("email" to IdentityAttributeValue.StringAttribute("poqwrol@email.com"))
            ).enqueue {
                when (it) {
                    is ErrorResult -> Log.v("ErrorResult", it.error.cause.toString())
                    is Success -> Log.v("Success", "YAAYYYYY")
                }
            }
        setContentView(R.layout.activity_main)
    }
}
