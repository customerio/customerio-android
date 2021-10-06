package io.customer.example

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import io.customer.base.data.ErrorResult
import io.customer.base.data.Success
import io.customer.sdk.CustomerIo
import io.customer.sdk.data.model.IdentityAttributeValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // there are two ways to identify customer

        // 1st way
        makeSynchronousRequest()

        // 2nd way
        makeAsynchronousRequest()
    }

    private fun makeAsynchronousRequest() {
        CustomerIo.instance()
            .identify(
                "identifier",
                attributes = mapOf("email" to IdentityAttributeValue.StringAttribute("sample@email.com"))
            ).enqueue {
                when (it) {
                    is ErrorResult -> Log.v("ErrorResult", it.error.cause.toString())
                    is Success -> Log.v("Success", "Success")
                }
            }
    }

    private fun makeSynchronousRequest() {
        CoroutineScope(Dispatchers.IO).launch {
            when (val result = CustomerIo.instance().identify("testcoo@email.com").execute()) {
                is ErrorResult -> Log.v("ErrorResult", result.error.cause.toString())
                is Success -> Log.v("Success", "Success")
            }
        }
    }
}
