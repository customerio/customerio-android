package io.customer.messagingpush.data.model

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Data class that holds payload for notifications from CIO dashboard
 *
 * @property extras data received in notification payload
 * @property deepLink url received in notification data
 * @property cioDeliveryId Customer.io message delivery id
 * @property cioDeliveryToken Customer.io message delivery token
 * @property title notification content title text
 * @property body notification content body text
 */
data class CustomerIOParsedPushPayload(
    val extras: Bundle,
    val deepLink: String?,
    val cioDeliveryId: String,
    val cioDeliveryToken: String,
    val title: String,
    val body: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        extras = parcel.readBundle(Bundle::class.java.classLoader) ?: Bundle(),
        deepLink = parcel.readString(),
        cioDeliveryId = parcel.readString().orEmpty(),
        cioDeliveryToken = parcel.readString().orEmpty(),
        title = parcel.readString().orEmpty(),
        body = parcel.readString().orEmpty()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeBundle(extras)
        parcel.writeString(deepLink)
        parcel.writeString(cioDeliveryId)
        parcel.writeString(cioDeliveryToken)
        parcel.writeString(title)
        parcel.writeString(body)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<CustomerIOParsedPushPayload> {
        override fun createFromParcel(parcel: Parcel): CustomerIOParsedPushPayload {
            return CustomerIOParsedPushPayload(parcel)
        }

        override fun newArray(size: Int): Array<CustomerIOParsedPushPayload?> {
            return arrayOfNulls(size)
        }
    }
}
