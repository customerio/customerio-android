package io.customer.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.customer.sdk.CustomerIO
import java.util.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        makeIdentifyRequest()

        // log events
        makeEventsRequests()
    }

    data class Fol(val a: String, val c: Int)

    private fun makeEventsRequests() {
        CustomerIO.instance().track(
            name = "string event",
            attributes = mapOf(
                "value" to "string test",
                "target" to 1
            )
        )
        CustomerIO.instance().track(
            name = "int event",
            attributes = mapOf("value" to 1388377266772)
        )
        CustomerIO.instance().track(
            name = "long event",
            attributes = mapOf("value" to 1653L)
        )
        CustomerIO.instance().track(
            name = "double event",
            attributes = mapOf("value" to 133333.882)
        )
        CustomerIO.instance().track(
            name = "array event",
            attributes = mapOf("value" to listOf("1", "2"))
        )
        CustomerIO.instance().track(
            name = "date event",
            attributes = mapOf("value" to Date())
        )
        CustomerIO.instance().track(
            name = "timestamp event",
            attributes = mapOf("value" to Date().time)
        )
        CustomerIO.instance().track(
            name = "custom class event",
            attributes = mapOf("value" to Fol(a = "aa", c = 1))
        )
        CustomerIO.instance().screen(
            name = "MainActivity"
        )
    }

    private fun makeIdentifyRequest() {
        CustomerIO.instance().identify(
            identifier = "support-ticket-test",
            mapOf("created_at" to 1642659790)
        )
    }
}
