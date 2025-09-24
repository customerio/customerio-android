package io.customer.messaginginapp.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

interface InAppPreferenceStore {
    fun saveNetworkResponse(url: String, response: String)
    fun getNetworkResponse(url: String): String?
    fun clearAll()

    // Broadcast message storage with expiry
    fun saveBroadcastMessages(messages: String, expiryTimeMillis: Long)
    fun getBroadcastMessages(): String?
    fun isBroadcastMessagesExpired(): Boolean

    // Simple broadcast tracking
    fun getBroadcastTimesShown(messageId: String): Int
    fun incrementBroadcastTimesShown(messageId: String)
    fun setBroadcastDismissed(messageId: String, dismissed: Boolean)
    fun isBroadcastDismissed(messageId: String): Boolean
    fun clearBroadcastTracking(messageId: String)
    fun clearAllBroadcastData()

    // Store ignoreDismiss flag separately to avoid JSON parsing
    fun setBroadcastIgnoreDismiss(messageId: String, ignoreDismiss: Boolean)
    fun getBroadcastIgnoreDismiss(messageId: String): Boolean

    // Delay functionality for temporary show restrictions
    fun setBroadcastNextShowTime(messageId: String, nextShowTimeMillis: Long)
    fun getBroadcastNextShowTime(messageId: String): Long
    fun isBroadcastInDelayPeriod(messageId: String): Boolean
}

internal class InAppPreferenceStoreImpl(
    context: Context
) : PreferenceStore(context), InAppPreferenceStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.inApp.${context.packageName}"
    }

    companion object {
        private const val BROADCAST_MESSAGES_KEY = "broadcast_messages"
        private const val BROADCAST_MESSAGES_EXPIRY_KEY = "broadcast_messages_expiry"
        private const val BROADCAST_TIMES_SHOWN_PREFIX = "broadcast_times_shown_"
        private const val BROADCAST_DISMISSED_PREFIX = "broadcast_dismissed_"
        private const val BROADCAST_IGNORE_DISMISS_PREFIX = "broadcast_ignore_dismiss_"
        private const val BROADCAST_NEXT_SHOW_TIME_PREFIX = "broadcast_next_show_time_"
    }

    override fun saveNetworkResponse(url: String, response: String) = prefs.edit {
        putString(url, response)
    }

    override fun getNetworkResponse(url: String): String? = prefs.read {
        getString(url, null)
    }

    override fun saveBroadcastMessages(messages: String, expiryTimeMillis: Long) = prefs.edit {
        putString(BROADCAST_MESSAGES_KEY, messages)
        putLong(BROADCAST_MESSAGES_EXPIRY_KEY, expiryTimeMillis)
    }

    override fun getBroadcastMessages(): String? {
        if (isBroadcastMessagesExpired()) {
            prefs.edit {
                remove(BROADCAST_MESSAGES_KEY)
                remove(BROADCAST_MESSAGES_EXPIRY_KEY)
            }
            return null
        }
        return prefs.read {
            getString(BROADCAST_MESSAGES_KEY, null)
        }
    }

    override fun isBroadcastMessagesExpired(): Boolean = prefs.read {
        val expiryTime = getLong(BROADCAST_MESSAGES_EXPIRY_KEY, 0)
        expiryTime > 0 && System.currentTimeMillis() > expiryTime
    } ?: false

    override fun getBroadcastTimesShown(messageId: String): Int = prefs.read {
        getInt("$BROADCAST_TIMES_SHOWN_PREFIX$messageId", 0)
    } ?: 0

    override fun incrementBroadcastTimesShown(messageId: String) = prefs.edit {
        val current = getBroadcastTimesShown(messageId)
        putInt("$BROADCAST_TIMES_SHOWN_PREFIX$messageId", current + 1)
    }

    override fun setBroadcastDismissed(messageId: String, dismissed: Boolean) = prefs.edit {
        if (dismissed) {
            putBoolean("$BROADCAST_DISMISSED_PREFIX$messageId", true)
        } else {
            remove("$BROADCAST_DISMISSED_PREFIX$messageId")
        }
    }

    override fun isBroadcastDismissed(messageId: String): Boolean = prefs.read {
        getBoolean("$BROADCAST_DISMISSED_PREFIX$messageId", false)
    } ?: false

    override fun clearBroadcastTracking(messageId: String) = prefs.edit {
        remove("$BROADCAST_TIMES_SHOWN_PREFIX$messageId")
        remove("$BROADCAST_DISMISSED_PREFIX$messageId")
        remove("$BROADCAST_IGNORE_DISMISS_PREFIX$messageId")
        remove("$BROADCAST_NEXT_SHOW_TIME_PREFIX$messageId")
    }

    override fun clearAllBroadcastData() = prefs.edit {
        remove(BROADCAST_MESSAGES_KEY)
        remove(BROADCAST_MESSAGES_EXPIRY_KEY)
        // Note: We intentionally keep individual message tracking (times shown, dismissed)
        // as they might be useful if the same broadcast comes back later
    }

    override fun setBroadcastIgnoreDismiss(messageId: String, ignoreDismiss: Boolean) = prefs.edit {
        putBoolean("$BROADCAST_IGNORE_DISMISS_PREFIX$messageId", ignoreDismiss)
    }

    override fun getBroadcastIgnoreDismiss(messageId: String): Boolean = prefs.read {
        getBoolean("$BROADCAST_IGNORE_DISMISS_PREFIX$messageId", false)
    } ?: false

    override fun setBroadcastNextShowTime(messageId: String, nextShowTimeMillis: Long) = prefs.edit {
        putLong("$BROADCAST_NEXT_SHOW_TIME_PREFIX$messageId", nextShowTimeMillis)
    }

    override fun getBroadcastNextShowTime(messageId: String): Long = prefs.read {
        getLong("$BROADCAST_NEXT_SHOW_TIME_PREFIX$messageId", 0)
    } ?: 0

    override fun isBroadcastInDelayPeriod(messageId: String): Boolean {
        val nextShowTime = getBroadcastNextShowTime(messageId)
        return nextShowTime > 0 && System.currentTimeMillis() < nextShowTime
    }
}
