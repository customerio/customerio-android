package io.customer.android.sample.kotlin_compose.ui.inline.recyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.customer.android.sample.kotlin_compose.R
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.inline.InlineMessageBaseView

class DynamicAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    internal var stickyHeaderInAppMessage: Message? = null
    internal var inlineInAppMessage: Message? = null
    internal var belowFoldInAppMessage: Message? = null

    override fun getItemCount() = ITEM_COUNT

    override fun getItemViewType(position: Int): Int {
        return if (position in INLINE_VIEW_IDS) TYPE_INLINE else TYPE_DUMMY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_INLINE) {
            val view = InlineMessageBaseView(parent.context)
            InlineViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            SimpleListItemViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is InlineViewHolder) {
            holder.itemView.id = INLINE_VIEW_IDS[position]!!
            when (position) {
                FIRST_INLINE_INDEX -> holder.bind(stickyHeaderInAppMessage)
                MIDDLE_INLINE_INDEX -> holder.bind(inlineInAppMessage)
                LAST_INLINE_INDEX -> holder.bind(belowFoldInAppMessage)
            }
        } else if (holder is SimpleListItemViewHolder) {
            holder.bind(position)
        }
    }

    companion object {
        private const val ITEM_COUNT = 15
        private const val TYPE_INLINE = 0
        private const val TYPE_DUMMY = 1

        const val FIRST_INLINE_INDEX = 0
        const val MIDDLE_INLINE_INDEX = ITEM_COUNT / 2
        const val LAST_INLINE_INDEX = ITEM_COUNT - 1

        val INLINE_VIEW_IDS = mapOf(
            FIRST_INLINE_INDEX to R.id.inline_message_top,
            MIDDLE_INLINE_INDEX to R.id.inline_message_middle,
            LAST_INLINE_INDEX to R.id.inline_message_bottom
        )
    }
}

class InlineViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    fun bind(message: Message?) {
        val inlineMessage = message ?: return

        val inlineMessageBaseView = itemView as InlineMessageBaseView
        inlineMessageBaseView.showMessage(inlineMessage)
    }
}

class SimpleListItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    fun bind(position: Int) {
        val text1 = itemView.findViewById<TextView>(android.R.id.text1)
        val text2 = itemView.findViewById<TextView>(android.R.id.text2)
        text1.text = "Item #$position"
        text2.text = "Subtitle for item"
    }
}
