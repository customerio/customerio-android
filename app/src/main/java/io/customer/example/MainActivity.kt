package io.customer.example

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import io.customer.base.comunication.Action
import io.customer.base.data.ErrorResult
import io.customer.base.data.Success
import io.customer.sdk.CustomerIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // there are two ways to identify customer

        // 1st way
        makeSynchronousRequest()

        // 2nd way
//        makeAsynchronousRequest()

        // log events
//        makeEventsRequests()

        // add custom attributes
        makeAddCustomDeviceAttributesRequest()

        // register device
        makeRegisterDeviceRequest()
    }

    private fun makeAddCustomDeviceAttributesRequest() {
        CustomerIO.instance().addCustomDeviceAttributes(mapOf("bingo" to "heyaa"))
    }

    private fun makeRegisterDeviceRequest() {
        CustomerIO.instance().registerDeviceToken("token").enqueue(outputCallback)
    }

    private val outputCallback = Action.Callback<Unit> { result ->
        when (result) {
            is ErrorResult -> Log.v("ErrorResult", result.error.getDisplayMessage())
            is Success -> Log.v("Success", "Success")
        }
    }

    data class Fol(val a: String, val c: Int)

    private fun makeEventsRequests() {
        CustomerIO.instance().track(
            name = "string event",
            attributes = mapOf(
                "value" to "string test",
                "target" to 1
            )
        ).enqueue(outputCallback)
        CustomerIO.instance().track(
            name = "int event",
            attributes = mapOf("value" to 1388377266772)
        ).enqueue(outputCallback)
        CustomerIO.instance().track(
            name = "long event",
            attributes = mapOf("value" to 1653L)
        ).enqueue(outputCallback)
        CustomerIO.instance().track(
            name = "double event",
            attributes = mapOf("value" to 133333.882)
        ).enqueue(outputCallback)
        CustomerIO.instance().track(
            name = "array event",
            attributes = mapOf("value" to listOf("1", "2"))
        ).enqueue(outputCallback)
        CustomerIO.instance().track(
            name = "date event",
            attributes = mapOf("value" to Date())
        ).enqueue(outputCallback)
        CustomerIO.instance().track(
            name = "timestamp event",
            attributes = mapOf("value" to Date().time)
        ).enqueue(outputCallback)
        CustomerIO.instance().track(
            name = "custom class event",
            attributes = mapOf("value" to Fol(a = "aa", c = 1))
        ).enqueue(outputCallback)
        CustomerIO.instance().screen(
            name = "MainActivity"
        ).enqueue(outputCallback)
    }

    private fun makeAsynchronousRequest() {
        CustomerIO.instance()
            .identify(
                identifier = "identifier",
                attributes = mapOf("email" to "testemail@email.com")
            ).enqueue(outputCallback)
    }

    private fun makeSynchronousRequest() {
        CoroutineScope(Dispatchers.IO).launch {
            when (
                val result =
                    CustomerIO.instance().identify(
                        identifier = "device-attri",
                        mapOf("created_at" to 1642659790)
                    ).execute()
            ) {
                is ErrorResult -> Log.v("ErrorResult", result.error.cause.toString())
                is Success -> Log.v("Success", "Success")
            }
        }
    }
}
