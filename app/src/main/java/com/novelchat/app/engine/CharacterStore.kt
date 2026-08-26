package com.novelchat.app.engine

import android.content.Context
import com.novelchat.app.model.Character
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色仓库：负责角色列表持久化（JSON 格式）
 *
 * 存储位置：context.filesDir/characters.json
 * 每个角色的训练模型：context/filesDir/characters/<id>.model
 * 每个角色的对话记忆：context.filesDir/characters/<id>.memory
 */
class CharacterStore(private val context: Context) {

    private val storeFile: File by lazy {
        File(context.filesDir, "characters.json")
    }

    private val charsDir: File by lazy {
        File(context.filesDir, "characters").also { if (!it.exists()) it.mkdirs() }
    }

    /** 读取所有角色 */
    fun loadAll(): MutableList<Character> {
        if (!storeFile.exists()) return mutableListOf()
        return try {
            val text = storeFile.readText(Charsets.UTF_8)
            val arr = JSONArray(text)
            (0 until arr.length()).map { idx ->
                jsonToCharacter(arr.getJSONObject(idx))
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** 保存全部角色 */
    fun saveAll(characters: List<Character>) {
        val arr = JSONArray()
        characters.forEach { arr.put(characterToJson(it)) }
        storeFile.writeText(arr.toString(), Charsets.UTF_8)
    }

    /** 获取角色对应的模型文件 */
    fun modelFile(characterId: String): File = File(charsDir, "$characterId.model")

    /** 获取角色对应的对话记忆文件 */
    fun memoryFile(characterId: String): File = File(charsDir, "$characterId.memory")

    /** 删除角色（包括模型与记忆） */
    fun deleteCharacter(characterId: String) {
        modelFile(characterId).takeIf { it.exists() }?.delete()
        memoryFile(characterId).takeIf { it.exists() }?.delete()
    }

    private fun characterToJson(c: Character): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("description", c.description)
        put("avatarColor", c.avatarColor)
        put("createdAt", c.createdAt)
        put("trainedChars", c.trainedChars)
        put("trainedSentences", c.trainedSentences)
        put("order", c.order)
        put("dialogueTurns", c.dialogueTurns)
        put("trainedFiles", JSONArray(c.trainedFiles))
    }

    private fun jsonToCharacter(obj: JSONObject): Character {
        val files = mutableListOf<String>()
        val filesArr = obj.optJSONArray("trainedFiles")
        if (filesArr != null) {
            for (i in 0 until filesArr.length()) files.add(filesArr.getString(i))
        }
        return Character(
            id = obj.getString("id"),
            name = obj.getString("name"),
            description = obj.optString("description", ""),
            avatarColor = obj.optInt("avatarColor", 0xFF6750A4.toInt()),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            trainedFiles = files,
            trainedChars = obj.optInt("trainedChars", 0),
            trainedSentences = obj.optInt("trainedSentences", 0),
            order = obj.optInt("order", 3),
            dialogueTurns = obj.optInt("dialogueTurns", 0)
        )
    }
}
