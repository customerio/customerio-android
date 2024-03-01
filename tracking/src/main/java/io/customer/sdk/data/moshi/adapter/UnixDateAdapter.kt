package io.customer.sdk.data.moshi.adapter

import com.squareup.moshi.*
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.base.extenstions.unixTimeToDate
import java.io.IOException
import java.util.*
import org.json.JSONObject.NULL

// Help from: https://github.com/square/moshi/blob/fd128875c308a90288e705162e03a835220a74d9/moshi-adapters/src/main/java/com/squareup/moshi/adapters/Rfc3339DateJsonAdapter.kt
// The Customer.io API uses unix date format. Use this date format for all Dates with JSON.
internal class UnixDateAdapter : JsonAdapter<Date>() {
    @Synchronized
    @Throws(IOException::class)
    @FromJson
    override fun fromJson(reader: JsonReader): Date? {
        if (reader.peek() == NULL) {
            return reader.nextNull()
        }
        val string = reader.nextString()
        return string.toLongOrNull()?.unixTimeToDate()
    }

    @Synchronized
    @Throws(IOException::class)
    @ToJson
    override fun toJson(writer: JsonWriter, value: Date?) {
        if (value == null) {
            writer.nullValue()
        } else {
            writer.value(value.getUnixTimestamp())
        }
    }
}
