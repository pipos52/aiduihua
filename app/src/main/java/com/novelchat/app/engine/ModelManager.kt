package com.novelchat.app.engine

import android.content.Context
import android.net.Uri
import com.novelchat.app.model.Character
import com.novelchat.app.model.MarkovModel
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * 模型管理器（按角色隔离）
 *
 * 负责角色级别的：
 * - TXT 读取与编码探测
 * - 模型训练（多本小说增量合并到同一角色）
 * - 模型与对话记忆的持久化
 * - 用户对话的增量学习（让角色逐渐记忆用户）
 */
class ModelManager(private val context: Context) {

    /** 进度回调 */
    interface ProgressListener {
        fun onProgress(progress: Int, message: String)
        fun onDone(model: MarkovModel, charCount: Int, sentenceCount: Int)
        fun onError(error: String)
    }

    private val store = CharacterStore(context)

    /** 从 Uri 读取 TXT 文本（自动探测编码） */
    fun readTxtFromUri(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法打开文件")
        return decodeText(bytes)
    }

    /** 从 Uri 获取文件显示名 */
    fun queryDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(
            uri, null, null, null, null
        ) ?: return null
        cursor.use {
            val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && it.moveToFirst()) return it.getString(nameIdx)
        }
        return uri.lastPathSegment
    }

    private fun decodeText(bytes: ByteArray): String {
        // 优先 UTF-8
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val data = if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(utf8Bom)) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes

        try {
            val s = String(data, Charsets.UTF_8)
            if (!s.contains("\uFFFD")) return s
        } catch (_: Exception) {
        }
        try {
            return String(data, charset("GBK"))
        } catch (_: Exception) {
        }
        return String(data, Charsets.UTF_8)
    }

    /**
     * 训练角色模型（增量合并到已存在的模型）
     *
     * @param text 小说文本
     * @param character 目标角色
     * @param fileName 用于记录的小说显示名
     * @param listener 进度回调
     */
    fun train(
        text: String,
        character: Character,
        fileName: String?,
        listener: ProgressListener
    ) {
        try {
            listener.onProgress(5, "初始化训练器...")
            val trainer = MarkovTrainer(order = character.order)

            // 加载已存在的模型（增量训练）
            var model: MarkovModel? = loadModel(character)
            if (model == null) {
                model = MarkovModel().also { it.order = character.order }
            } else if (model.order != character.order) {
                // 阶数变更：重新训练
                model = MarkovModel().also { it.order = character.order }
            }

            val totalChars = text.length
            val chunkSize = 200_000
            var processed = 0
            var start = 0
            while (start < totalChars) {
                val end = minOf(start + chunkSize, totalChars)
                val chunk = text.substring(start, end)
                model = trainer.train(chunk, model)
                processed = end
                val progress = 5 + (processed * 90 / totalChars)
                listener.onProgress(progress, "已训练 $processed / $totalChars 字")
                start = end
            }

            // 记录训练的小说
            if (fileName != null && !character.trainedFiles.contains(fileName)) {
                character.trainedFiles.add(fileName)
            }
            character.trainedChars = model.trainedChars
            character.trainedSentences = model.trainedSentences

            saveModel(character, model)
            listener.onProgress(100, "训练完成")
            listener.onDone(model, model.trainedChars, model.trainedSentences)
        } catch (e: Exception) {
            listener.onError("训练出错：${e.message}")
        }
    }

    /**
     * 把一次用户-角色对话增量学习到模型
     *
     * 让角色"记住"用户的问法与自己的回复，
     * 下次遇到相似问法时倾向于给出类似回答。
     */
    fun learnFromDialogue(
        character: Character,
        userText: String,
        botText: String,
        memory: DialogueMemory
    ): MarkovModel? {
        val model = loadModel(character) ?: return null
        MarkovTrainer(order = character.order).learnFromDialogue(userText, botText, model)
        character.dialogueTurns++
        saveModel(character, model)
        // 记忆文件持久化
        memory.append(com.novelchat.app.model.ChatMessage(userText, isUser = true))
        memory.append(com.novelchat.app.model.ChatMessage(botText, isUser = false))
        return model
    }

    /** 加载角色模型 */
    fun loadModel(character: Character): MarkovModel? {
        val file = store.modelFile(character.id)
        if (!file.exists()) return null
        return try {
            ObjectInputStream(file.inputStream()).use { ois ->
                ois.readObject() as? MarkovModel
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 保存角色模型 */
    fun saveModel(character: Character, model: MarkovModel) {
        val file = store.modelFile(character.id)
        ObjectOutputStream(file.outputStream()).use { oos -> oos.writeObject(model) }
    }

    /** 获取角色对话记忆 */
    fun memoryOf(character: Character): DialogueMemory =
        DialogueMemory(store.memoryFile(character.id))

    /** 删除角色（包括模型与记忆） */
    fun deleteCharacter(characterId: String) {
        store.deleteCharacter(characterId)
    }
}
