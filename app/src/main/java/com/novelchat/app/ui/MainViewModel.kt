package com.novelchat.app.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelchat.app.engine.CharacterStore
import com.novelchat.app.engine.DialogueMemory
import com.novelchat.app.engine.ModelManager
import com.novelchat.app.engine.TextGenerator
import com.novelchat.app.model.Character
import com.novelchat.app.model.ChatMessage
import com.novelchat.app.model.MarkovModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天界面状态机（按角色隔离）
 *
 * 每次启动根据传入的 character_id 加载该角色的：
 * - 训练模型
 * - 对话记忆（短期窗口用于上下文回溯）
 *
 * 用户与角色对话时：
 * - 即时生成回复（多候选 + 主题召回 + 问答硬记忆）
 * - 把"问 + 答"回灌训练到模型（增量学习）
 * - 把对话追加到记忆文件（持久化）
 *
 * 角色之间数据完全隔离。
 */
class MainViewModel : ViewModel() {

    /** 当前角色 */
    private val _character = MutableLiveData<Character?>()
    val character: LiveData<Character?> = _character

    /** 当前训练状态消息 */
    private val _statusText = MutableLiveData("未训练")
    val statusText: LiveData<String> = _statusText

    /** 训练进度 (0-100) */
    private val _progress = MutableLiveData(0)
    val progress: LiveData<Int> = _progress

    /** 进度条可见性 */
    private val _progressVisible = MutableLiveData(false)
    val progressVisible: LiveData<Boolean> = _progressVisible

    /** 聊天消息列表（实时显示，与记忆文件解耦） */
    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    /** Toast 提示事件 */
    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    /** 当前角色的模型（内存缓存） */
    private var model: MarkovModel? = null

    /** 当前角色的对话记忆 */
    private var memory: DialogueMemory? = null

    /** 聊天历史本地真相源 */
    private val messagesList = mutableListOf<ChatMessage>()
    private val messagesLock = Any()

    private lateinit var manager: ModelManager
    private lateinit var store: CharacterStore

    fun init(context: android.content.Context, characterId: String?) {
        manager = ModelManager(context)
        store = CharacterStore(context)

        if (characterId == null) {
            _toast.value = "请先在角色列表中创建并选择一个角色"
            return
        }

        val character = store.loadAll().find { it.id == characterId }
        if (character == null) {
            _toast.value = "找不到角色"
            return
        }
        _character.value = character

        memory = manager.memoryOf(character)
        model = manager.loadModel(character)
        updateStatus()

        // 从记忆文件恢复对话历史显示
        val history = memory?.loadAll() ?: emptyList()
        synchronized(messagesLock) {
            messagesList.clear()
            messagesList.addAll(history)
            _messages.value = messagesList.toList()
        }

        if (model?.isReady() == true) {
            pushBotMessage("[${character.name}] 你好，可以开始对话了。" +
                    if (character.trainedFiles.size > 1)
                        " 我已学习了 ${character.trainedFiles.size} 本小说。"
                    else "")
        } else {
            pushBotMessage("「${character.name}」尚未训练。" +
                    "返回角色列表，点该角色的菜单 -> 继续训练（导入TXT），可一本一本导入多本小说。")
        }
    }

    /**
     * 安全地持久化当前角色到角色列表（避免并发覆盖）
     */
    private fun persistCharacter(character: Character) {
        val list = store.loadAll()
        val idx = list.indexOfFirst { it.id == character.id }
        if (idx >= 0) {
            list[idx] = character
            store.saveAll(list)
        }
    }

    /**
     * 用从角色列表传入的 Uri 直接开始训练
     */
    fun trainFromUri(uri: android.net.Uri, fileName: String) {
        val character = _character.value ?: run {
            _toast.value = "未选择角色"
            return
        }
        _progressVisible.value = true
        _progress.value = 0
        _statusText.value = "读取文件..."

        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { manager.readTxtFromUri(uri) }
                    .getOrElse { e ->
                        _progressVisible.postValue(false)
                        _toast.postValue("读取文件失败：${e.message}")
                        return@withContext null
                    }
            } ?: return@launch

            if (text.length < 100) {
                _progressVisible.value = false
                _toast.value = "文件太小，请选择有效的小说文本"
                return@launch
            }

            withContext(Dispatchers.IO) {
                manager.train(text, character, fileName, object : ModelManager.ProgressListener {
                    override fun onProgress(progress: Int, message: String) {
                        _progress.postValue(progress)
                        _statusText.postValue("训练中 $progress%")
                    }

                    override fun onDone(model: MarkovModel, charCount: Int, sentenceCount: Int) {
                        this@MainViewModel.model = model
                        persistCharacter(character)
                        _character.postValue(character)
                        _progressVisible.postValue(false)
                        updateStatus()
                        pushBotMessage(
                            "训练完成：${charCount} 字 / ${sentenceCount} 句。\n" +
                                    "已学习小说：「$fileName」\n" +
                                    "总计 ${character.trainedFiles.size} 本，${character.trainedChars} 字。"
                        )
                    }

                    override fun onError(error: String) {
                        _progressVisible.postValue(false)
                        _statusText.postValue("训练失败")
                        _toast.postValue(error)
                    }
                })
            }
        }
    }

    /**
     * 处理用户发送的消息
     *
     * 流程：
     * 1. 用户消息入显示与记忆
     * 2. 后台生成回复
     * 3. 把"问 + 答"增量训练到模型（让角色记忆用户）
     * 4. 持久化记忆
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val character = _character.value
        if (character == null) {
            _toast.value = "请先在角色列表中选择角色"
            return
        }

        addUserMessage(trimmed)

        viewModelScope.launch(Dispatchers.IO) {
            val currentModel = model
            val currentMemory = memory
            val reply = if (currentModel != null && currentModel.isReady()) {
                TextGenerator(currentModel, currentMemory).reply(trimmed)
            } else {
                "请先训练模型再开始对话。"
            }
            pushBotMessage(reply)

            // 增量学习：把这次对话回灌训练
            if (currentModel != null && currentModel.isReady() && currentMemory != null) {
                manager.learnFromDialogue(character, trimmed, reply, currentMemory)
                character.dialogueTurns++
                persistCharacter(character)
            }
        }
    }

    fun clearChat() {
        synchronized(messagesLock) {
            messagesList.clear()
            _messages.postValue(emptyList())
        }
        memory?.clear()
        _toast.value = "已清空当前对话历史（不影响已训练的模型）"
    }

    private fun addUserMessage(text: String) {
        synchronized(messagesLock) {
            messagesList.add(ChatMessage(text, isUser = true))
            _messages.postValue(messagesList.toList())
        }
    }

    private fun pushBotMessage(text: String) {
        synchronized(messagesLock) {
            messagesList.add(ChatMessage(text, isUser = false))
            _messages.postValue(messagesList.toList())
        }
    }

    private fun updateStatus() {
        val c = _character.value
        val m = model
        if (c == null || m == null || !m.isReady()) {
            _statusText.postValue("未训练")
        } else {
            val fileCount = c.trainedFiles.size
            _statusText.postValue(
                "已就绪 · ${fileCount}本 · ${c.trainedChars}字 / ${c.trainedSentences}句" +
                        if (c.dialogueTurns > 0) " · 记忆${c.dialogueTurns}轮" else ""
            )
        }
    }

    fun consumeToast() {
        _toast.value = null
    }
}
