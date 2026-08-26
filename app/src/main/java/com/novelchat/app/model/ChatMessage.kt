package com.novelchat.app.model

/**
 * 聊天消息数据类
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
