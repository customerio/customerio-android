package io.customer.messaginginapp.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

internal interface InAppPreferenceStore {
    fun saveNetworkResponse(url: String, response: String)
    fun getNetworkResponse(url: String): String?
    fun clearAll()

    // Anonymous message storage with expiry
    fun saveAnonymousMessages(messages: String, expiryTimeMillis: Long)
    fun getAnonymousMessages(): String?
    fun isAnonymousMessagesExpired(): Boolean

    // Simple anonymous message tracking
    fun getAnonymousTimesShown(messageId: String): Int
    fun incrementAnonymousTimesShown(messageId: String)
    fun setAnonymousDismissed(messageId: String, dismissed: Boolean)
    fun isAnonymousDismissed(messageId: String): Boolean
    fun clearAnonymousTracking(messageId: String)
    fun clearAllAnonymousData()

    // Delay functionality for temporary show restrictions
    fun setAnonymousNextShowTime(messageId: String, nextShowTimeMillis: Long)
    fun getAnonymousNextShowTime(messageId: String): Long
    fun isAnonymousInDelayPeriod(messageId: String): Boolean

    // Inbox message opened status caching
    fun saveInboxMessageOpenedStatus(queueId: String, opened: Boolean)
    fun getInboxMessageOpenedStatus(queueId: String): Boolean?
    fun clearInboxMessageOpenedStatus(queueId: String)
}

internal class InAppPreferenceStoreImpl(
    context: Context
) : PreferenceStore(context), InAppPreferenceStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.inApp.${context.packageName}"
    }

    companion object {
        // Keep string values as "broadcast_*" for backward compatibility with existing user data
        private const val ANONYMOUS_MESSAGES_KEY = "broadcast_messages"
        private const val ANONYMOUS_MESSAGES_EXPIRY_KEY = "broadcast_messages_expiry"
        private const val ANONYMOUS_TIMES_SHOWN_PREFIX = "broadcast_times_shown_"
        private const val ANONYMOUS_DISMISSED_PREFIX = "broadcast_dismissed_"
        private const val ANONYMOUS_NEXT_SHOW_TIME_PREFIX = "broadcast_next_show_time_"
        private const val INBOX_MESSAGE_OPENED_PREFIX = "inbox_message_opened_"
    }

    override fun saveNetworkResponse(url: String, response: String) = prefs.edit {
        putString(url, response)
    }

    override fun getNetworkResponse(url: String): String? = prefs.read {
        getString(url, null)
    }

    override fun saveAnonymousMessages(messages: String, expiryTimeMillis: Long) = prefs.edit {
        putString(ANONYMOUS_MESSAGES_KEY, messages)
        putLong(ANONYMOUS_MESSAGES_EXPIRY_KEY, expiryTimeMillis)
    }

    override fun getAnonymousMessages(): String? {
        if (isAnonymousMessagesExpired()) {
            clearAnonymousMessages()
            return null
        }
        return getAnonymousMessagesRaw()
    }

    private fun getAnonymousMessagesRaw(): String? = prefs.read {
        getString(ANONYMOUS_MESSAGES_KEY, null)
    }

    private fun getAnonymousMessagesExpiry(): Long = prefs.read {
        getLong(ANONYMOUS_MESSAGES_EXPIRY_KEY, 0)
    } ?: 0

    private fun clearAnonymousMessages() = prefs.edit {
        remove(ANONYMOUS_MESSAGES_KEY)
        remove(ANONYMOUS_MESSAGES_EXPIRY_KEY)
    }

    override fun isAnonymousMessagesExpired(): Boolean = prefs.read {
        val expiryTime = getLong(ANONYMOUS_MESSAGES_EXPIRY_KEY, 0)
        expiryTime > 0 && System.currentTimeMillis() > expiryTime
    } ?: false

    override fun getAnonymousTimesShown(messageId: String): Int = prefs.read {
        getInt("$ANONYMOUS_TIMES_SHOWN_PREFIX$messageId", 0)
    } ?: 0

    override fun incrementAnonymousTimesShown(messageId: String) = prefs.edit {
        val current = getAnonymousTimesShown(messageId)
        putInt("$ANONYMOUS_TIMES_SHOWN_PREFIX$messageId", current + 1)
    }

    override fun setAnonymousDismissed(messageId: String, dismissed: Boolean) = prefs.edit {
        if (dismissed) {
            putBoolean("$ANONYMOUS_DISMISSED_PREFIX$messageId", true)
        } else {
            remove("$ANONYMOUS_DISMISSED_PREFIX$messageId")
        }
    }

    override fun isAnonymousDismissed(messageId: String): Boolean = prefs.read {
        getBoolean("$ANONYMOUS_DISMISSED_PREFIX$messageId", false)
    } ?: false

    override fun clearAnonymousTracking(messageId: String) = prefs.edit {
        remove("$ANONYMOUS_TIMES_SHOWN_PREFIX$messageId")
        remove("$ANONYMOUS_DISMISSED_PREFIX$messageId")
        remove("$ANONYMOUS_NEXT_SHOW_TIME_PREFIX$messageId")
    }

    override fun clearAllAnonymousData() = prefs.edit {
        remove(ANONYMOUS_MESSAGES_KEY)
        remove(ANONYMOUS_MESSAGES_EXPIRY_KEY)
        // Note: Individual message tracking is cleared by AnonymousMessageManager
        // which calls clearAnonymousTracking() for each previous message
    }

    override fun setAnonymousNextShowTime(messageId: String, nextShowTimeMillis: Long) = prefs.edit {
        putLong("$ANONYMOUS_NEXT_SHOW_TIME_PREFIX$messageId", nextShowTimeMillis)
    }

    override fun getAnonymousNextShowTime(messageId: String): Long = prefs.read {
        getLong("$ANONYMOUS_NEXT_SHOW_TIME_PREFIX$messageId", 0)
    } ?: 0

    override fun isAnonymousInDelayPeriod(messageId: String): Boolean {
        val nextShowTime = getAnonymousNextShowTime(messageId)
        return nextShowTime > 0 && System.currentTimeMillis() < nextShowTime
    }

    override fun saveInboxMessageOpenedStatus(queueId: String, opened: Boolean) = prefs.edit {
        putBoolean("$INBOX_MESSAGE_OPENED_PREFIX$queueId", opened)
    }

    override fun getInboxMessageOpenedStatus(queueId: String): Boolean? = prefs.read {
        if (contains("$INBOX_MESSAGE_OPENED_PREFIX$queueId")) {
            getBoolean("$INBOX_MESSAGE_OPENED_PREFIX$queueId", false)
        } else {
            null
        }
    }

    override fun clearInboxMessageOpenedStatus(queueId: String) = prefs.edit {
        remove("$INBOX_MESSAGE_OPENED_PREFIX$queueId")
    }
}
