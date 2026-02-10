package io.customer.android.sample.java_layout.ui.inbox

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.customer.android.sample.java_layout.R
import io.customer.android.sample.java_layout.databinding.ItemInboxMessageBinding
import io.customer.messaginginapp.gist.data.model.InboxMessage
import java.text.SimpleDateFormat
import java.util.Locale

class InboxMessagesAdapter(
    private val onToggleReadClick: (InboxMessage) -> Unit,
    private val onTrackClickClick: (InboxMessage) -> Unit,
    private val onDeleteClick: (InboxMessage) -> Unit
) : RecyclerView.Adapter<InboxMessagesAdapter.InboxMessageViewHolder>() {

    private val messages: MutableList<InboxMessage> = mutableListOf()

    fun setMessages(newMessages: List<InboxMessage>) {
        val diffCallback = InboxMessageDiffCallback(messages, newMessages)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        messages.clear()
        messages.addAll(newMessages)
        diffResult.dispatchUpdatesTo(this)
    }

    private class InboxMessageDiffCallback(
        private val oldList: List<InboxMessage>,
        private val newList: List<InboxMessage>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].deliveryId == newList[newItemPosition].deliveryId
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            // InboxMessage is a data class with generated equals() that compares all fields
            // This ensures all displayed fields are checked for changes
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InboxMessageViewHolder {
        val binding = ItemInboxMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return InboxMessageViewHolder(binding, onToggleReadClick, onTrackClickClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: InboxMessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    class InboxMessageViewHolder(
        private val binding: ItemInboxMessageBinding,
        private val onToggleReadClick: (InboxMessage) -> Unit,
        private val onTrackClickClick: (InboxMessage) -> Unit,
        private val onDeleteClick: (InboxMessage) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

        fun bind(message: InboxMessage) {
            val context = binding.root.context

            binding.deliveryIdTextView.text = message.deliveryId ?: "N/A - (${message.queueId})"

            val backgroundColorAttr = if (message.opened) {
                com.google.android.material.R.attr.colorSurfaceContainerLowest
            } else {
                com.google.android.material.R.attr.colorSurfaceContainerHighest
            }
            binding.cardView.setCardBackgroundColor(
                android.util.TypedValue().let { typedValue ->
                    context.theme.resolveAttribute(backgroundColorAttr, typedValue, true)
                    typedValue.data
                }
            )

            // Compact metadata line: "sentAt • Priority X • topics"
            val metadataParts = mutableListOf<String>()

            // Add sent date
            metadataParts.add(formatDate(message.sentAt))

            // Add priority if present
            message.priority?.let { metadataParts.add("Priority $it") }

            // Add topics
            if (message.topics.isNotEmpty()) {
                metadataParts.add(message.topics.joinToString(", "))
            }

            binding.metadataTextView.text = metadataParts.joinToString(" • ")

            // Properties (show only if present)
            val properties = message.properties
            if (properties.isNotEmpty()) {
                binding.propertiesTextView.text = formatProperties(properties)
                binding.propertiesTextView.visibility = android.view.View.VISIBLE
            } else {
                binding.propertiesTextView.visibility = android.view.View.GONE
                binding.propertiesTextView.text = ""
            }

            // Show only the relevant button based on message state
            if (message.opened) {
                // Read message - show mark as unread button
                binding.markReadButton.visibility = android.view.View.GONE
                binding.markUnreadButton.visibility = android.view.View.VISIBLE

                binding.markUnreadButton.setOnClickListener {
                    onToggleReadClick(message)
                }

                binding.markUnreadButton.setOnLongClickListener {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.inbox_mark_as_unread),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            } else {
                // Unread message - show mark as read button
                binding.markReadButton.visibility = android.view.View.VISIBLE
                binding.markUnreadButton.visibility = android.view.View.GONE

                binding.markReadButton.setOnClickListener {
                    onToggleReadClick(message)
                }

                binding.markReadButton.setOnLongClickListener {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.inbox_mark_as_read),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }

            // Track click button (always visible)
            binding.trackClickButton.setOnClickListener {
                onTrackClickClick(message)
            }

            binding.trackClickButton.setOnLongClickListener {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.inbox_track_click),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                true
            }

            // Delete button (always visible)
            binding.deleteButton.setOnClickListener {
                onDeleteClick(message)
            }

            binding.deleteButton.setOnLongClickListener {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.inbox_delete),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                true
            }
        }

        private fun formatDate(date: java.util.Date): String {
            val now = System.currentTimeMillis()
            val dateTime = date.time

            // Use relative time if within the last week
            return if (now - dateTime < DateUtils.WEEK_IN_MILLIS) {
                DateUtils.getRelativeTimeSpanString(
                    dateTime,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            } else {
                // Otherwise show formatted date
                dateFormatter.format(date)
            }
        }

        private fun formatProperties(properties: Map<String, Any?>): String {
            if (properties.isEmpty()) return "{}"

            return properties.entries.joinToString(
                separator = ", ",
                prefix = "{",
                postfix = "}"
            ) { (key, value) ->
                val formattedValue = when (value) {
                    is String -> "\"$value\""
                    else -> value.toString()
                }
                "\"$key\": $formattedValue"
            }
        }
    }
}
