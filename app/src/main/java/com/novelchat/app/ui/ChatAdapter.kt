package com.novelchat.app.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.novelchat.app.R
import com.novelchat.app.model.ChatMessage

/**
 * 聊天消息列表适配器
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val items = mutableListOf<ChatMessage>()

    fun submit(messages: List<ChatMessage>) {
        val prev = items.size
        items.clear()
        items.addAll(messages)
        if (prev == 0 && items.isNotEmpty()) {
            notifyItemRangeInserted(0, items.size)
        } else {
            notifyDataSetChanged()
        }
    }

    fun add(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)

        fun bind(message: ChatMessage) {
            tvMessage.text = message.text
            val params = tvMessage.layoutParams as FrameLayout.LayoutParams
            if (message.isUser) {
                params.gravity = Gravity.END
                tvMessage.setBackgroundResource(R.drawable.bg_bubble_user)
                tvMessage.setTextColor(itemView.context.getColor(R.color.white))
            } else {
                params.gravity = Gravity.START
                tvMessage.setBackgroundResource(R.drawable.bg_bubble_bot)
                tvMessage.setTextColor(itemView.context.getColor(R.color.text_primary))
            }
            tvMessage.layoutParams = params
        }
    }
}
