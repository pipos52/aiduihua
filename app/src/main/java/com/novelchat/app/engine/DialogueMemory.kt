package com.novelchat.app.engine

import com.novelchat.app.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 对话记忆系统
 *
 * 负责持久化每个角色与用户的对话历史，并维护一个滚动窗口的
 * 短期记忆，供 TextGenerator 进行上下文回溯。
 *
 * 存储：JSON Lines 文件（每行一条消息），易于追加与流式读取。
 */
class DialogueMemory(private val memoryFile: File) {

    /** 短期记忆窗口大小（保留最近 N 条消息） */
    var windowSize: Int = 20

    init {
        if (!memoryFile.exists()) {
            memoryFile.parentFile?.mkdirs()
            memoryFile.createNewFile()
        }
    }

    /** 追加一条消息到记忆文件 */
    fun append(message: ChatMessage) {
        val obj = JSONObject().apply {
            put("text", message.text)
            put("isUser", message.isUser)
            put("ts", message.timestamp)
        }
        // 追加写：以 JSONL 形式
        memoryFile.appendText(obj.toString() + "\n", Charsets.UTF_8)
    }

    /** 读取所有历史消息 */
    fun loadAll(): List<ChatMessage> {
        if (!memoryFile.exists()) return emptyList()
        val list = mutableListOf<ChatMessage>()
        memoryFile.useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                runCatching {
                    val obj = JSONObject(line)
                    list.add(
                        ChatMessage(
                            text = obj.getString("text"),
                            isUser = obj.getBoolean("isUser"),
                            timestamp = obj.optLong("ts", System.currentTimeMillis())
                        )
                    )
                }
            }
        }
        return list
    }

    /** 读取最近 windowSize 条消息作为短期记忆 */
    fun recentWindow(): List<ChatMessage> {
        val all = loadAll()
        return if (all.size <= windowSize) all
        else all.subList(all.size - windowSize, all.size)
    }

    /**
     * 提取最近 K 轮用户输入作为上下文种子来源
     */
    fun recentUserInputs(k: Int = 3): List<String> {
        return recentWindow()
            .filter { it.isUser }
            .takeLast(k)
            .map { it.text }
    }

    /**
     * 提取最近 K 轮角色回复作为风格参考
     */
    fun recentBotReplies(k: Int = 3): List<String> {
        return recentWindow()
            .filter { !it.isUser }
            .takeLast(k)
            .map { it.text }
    }

    /** 清空记忆文件 */
    fun clear() {
        memoryFile.writeText("", Charsets.UTF_8)
    }

    /** 导出全部记忆为 JSON 字符串（用于调试或备份） */
    fun exportJson(): String {
        val arr = JSONArray()
        loadAll().forEach { msg ->
            arr.put(JSONObject().apply {
                put("text", msg.text)
                put("isUser", msg.isUser)
                put("ts", msg.timestamp)
            })
        }
        return arr.toString(2)
    }
}
