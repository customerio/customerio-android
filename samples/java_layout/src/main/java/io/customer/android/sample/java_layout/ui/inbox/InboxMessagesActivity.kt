package io.customer.android.sample.java_layout.ui.inbox

import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.customer.android.sample.java_layout.R
import io.customer.android.sample.java_layout.databinding.ActivityInboxMessagesBinding
import io.customer.android.sample.java_layout.ui.core.BaseActivity
import io.customer.messaginginapp.di.inAppMessaging
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.inbox.InboxChangeListener
import io.customer.sdk.CustomerIO

class InboxMessagesActivity : BaseActivity<ActivityInboxMessagesBinding>() {
    private lateinit var adapter: InboxMessagesAdapter
    private val messageInbox by lazy { CustomerIO.instance().inAppMessaging().inbox() }
    private var inboxChangeListener: InboxChangeListener? = null

    override fun inflateViewBinding(): ActivityInboxMessagesBinding {
        return ActivityInboxMessagesBinding.inflate(layoutInflater)
    }

    override fun setupContent() {
        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupInbox()
        fetchMessages()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = InboxMessagesAdapter(
            onToggleReadClick = { message ->
                if (message.opened) {
                    messageInbox.markMessageUnopened(message)
                } else {
                    messageInbox.markMessageOpened(message)
                }
            },
            onTrackClickClick = { message ->
                showTrackClickDialog(message)
            },
            onDeleteClick = { message ->
                showDeleteConfirmationDialog(message)
            }
        )
        binding.recyclerView.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener { fetchMessages() }
    }

    private fun setupInbox() {
        inboxChangeListener = object : InboxChangeListener {
            override fun onInboxChanged(messages: List<InboxMessage>) {
                updateMessages(messages)
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }

        inboxChangeListener?.let { messageInbox.addChangeListener(it) }
    }

    private fun fetchMessages() {
        showLoading()
        messageInbox.getMessages({ result ->
            runOnUiThread {
                result.onSuccess { messages ->
                    updateMessages(messages)
                    hideLoading()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        getString(R.string.inbox_fetch_error) + ": ${error.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateMessages(emptyList())
                    hideLoading()
                }
                binding.swipeRefreshLayout.isRefreshing = false
            }
        })
    }

    private fun updateMessages(messages: List<InboxMessage>) {
        if (messages.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyStateTextView.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyStateTextView.visibility = View.GONE
        }
        adapter.setMessages(messages)
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.emptyStateTextView.visibility = View.GONE
    }

    private fun hideLoading() {
        binding.progressBar.visibility = View.GONE
    }

    private fun showTrackClickDialog(message: InboxMessage) {
        val input = EditText(this).apply {
            hint = getString(R.string.inbox_track_click_dialog_hint)
            setPadding(64, 32, 64, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.inbox_track_click_dialog_title)
            .setMessage(R.string.inbox_track_click_dialog_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val actionName = input.text.toString().trim()
                if (actionName.isEmpty()) {
                    messageInbox.trackMessageClicked(message)
                } else {
                    messageInbox.trackMessageClicked(message, actionName)
                }
                Toast.makeText(
                    this,
                    getString(R.string.inbox_track_click_success),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmationDialog(message: InboxMessage) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.inbox_delete_dialog_title)
            .setMessage(R.string.inbox_delete_dialog_message)
            .setPositiveButton(R.string.inbox_delete_confirm) { _, _ ->
                messageInbox.markMessageDeleted(message)
                Toast.makeText(
                    this,
                    getString(R.string.inbox_message_deleted),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.inbox_delete_cancel, null)
            .show()
    }

    override fun onDestroy() {
        inboxChangeListener?.let { messageInbox.removeChangeListener(it) }
        super.onDestroy()
    }
}
