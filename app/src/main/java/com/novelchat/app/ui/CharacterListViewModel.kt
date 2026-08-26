package com.novelchat.app.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelchat.app.engine.CharacterStore
import com.novelchat.app.model.Character
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 角色列表 ViewModel
 */
class CharacterListViewModel : ViewModel() {

    private val _characters = MutableLiveData<List<Character>>(emptyList())
    val characters: LiveData<List<Character>> = _characters

    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    private lateinit var store: CharacterStore

    fun init(context: android.content.Context) {
        store = CharacterStore(context)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.loadAll()
            _characters.postValue(list)
        }
    }

    fun createCharacter(name: String, description: String, order: Int) {
        if (name.isBlank()) {
            _toast.value = "请输入角色名称"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val c = Character(
                id = Character.newId(),
                name = name.trim(),
                description = description.trim(),
                order = order
            )
            val list = store.loadAll()
            list.add(c)
            store.saveAll(list)
            _characters.postValue(list)
            _toast.postValue("已创建角色：${c.name}")
        }
    }

    fun renameCharacter(c: Character, newName: String) {
        if (newName.isBlank()) {
            _toast.value = "名称不能为空"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.loadAll()
            list.find { it.id == c.id }?.let {
                it.name = newName.trim()
            }
            store.saveAll(list)
            _characters.postValue(list)
        }
    }

    fun deleteCharacter(c: Character) {
        viewModelScope.launch(Dispatchers.IO) {
            // 删除模型与记忆文件
            store.deleteCharacter(c.id)
            // 从角色列表移除
            val list = store.loadAll()
            list.removeAll { it.id == c.id }
            store.saveAll(list)
            _characters.postValue(list)
            _toast.postValue("已删除角色：${c.name}")
        }
    }

    fun saveCharacter(c: Character) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.loadAll()
            val idx = list.indexOfFirst { it.id == c.id }
            if (idx >= 0) {
                list[idx] = c
                store.saveAll(list)
            }
        }
    }

    /**
     * 把传入角色合并保存到现有列表（保留其他角色的最新状态）
     */
    fun upsertCharacter(c: Character) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.loadAll()
            val idx = list.indexOfFirst { it.id == c.id }
            if (idx >= 0) list[idx] = c else list.add(c)
            store.saveAll(list)
            _characters.postValue(list)
        }
    }

    fun consumeToast() {
        _toast.value = null
    }
}
