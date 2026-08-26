package com.novelchat.app.model

import java.io.Serializable

/**
 * 角色数据类
 *
 * 每个角色拥有独立的训练模型与对话记忆，角色之间数据完全隔离。
 * 一个角色可关联多本 TXT 小说（增量训练融合到同一模型）。
 */
data class Character(
    val id: String,
    var name: String,
    var description: String = "",
    var avatarColor: Int = 0xFF6750A4.toInt(),
    val createdAt: Long = System.currentTimeMillis(),
    /** 已训练的小说文件显示名列表（用于 UI 展示） */
    val trainedFiles: MutableList<String> = mutableListOf(),
    /** 已训练字数 */
    var trainedChars: Int = 0,
    /** 已训练句数 */
    var trainedSentences: Int = 0,
    /** 模型阶数（N-gram）。字级别下 N 越大越通顺；默认 5 是生成连贯中文的一个较优值 */
    var order: Int = 5,
    /** 记忆的对话轮数（与用户交互的次数） */
    var dialogueTurns: Int = 0
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 20240102L

        /** 生成新角色 ID */
        fun newId(): String = "char_" + System.currentTimeMillis().toString(36) +
                "_" + (1..6).map { ('a'..'z').random() }.joinToString("")
    }
}
