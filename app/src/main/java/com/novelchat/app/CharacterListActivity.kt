package com.novelchat.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.novelchat.app.databinding.ActivityCharacterListBinding
import com.novelchat.app.model.Character
import com.novelchat.app.ui.CharacterAdapter
import com.novelchat.app.ui.CharacterListViewModel

/**
 * 角色管理界面
 *
 * - 列出所有角色（点选进入聊天）
 * - 创建新角色（指定名称、描述、阶数）
 * - 继续训练（追加 TXT 到同一角色）
 * - 重命名 / 删除角色
 */
class CharacterListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCharacterListBinding
    private val viewModel: CharacterListViewModel by viewModels()
    private lateinit var adapter: CharacterAdapter

    /** 用于"继续训练"的中转：记录当前要训练的角色 */
    private var pendingTrainCharacter: Character? = null

    private val openTxtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val target = pendingTrainCharacter
        if (uri != null && target != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            // 进入聊天页，并附带 Uri 让其完成训练
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("character_id", target.id)
                putExtra("train_uri", uri.toString())
                putExtra("train_file_name", queryFileName(uri) ?: "未命名.txt")
            }
            startActivity(intent)
        }
        pendingTrainCharacter = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.init(applicationContext)

        adapter = CharacterAdapter(
            characters = mutableListOf(),
            onTrainClick = { c ->
                pendingTrainCharacter = c
                openTxtLauncher.launch(arrayOf("text/plain", "*/*"))
            },
            onDeleteClick = { c -> confirmDelete(c) },
            onRenameClick = { c -> showRenameDialog(c) }
        )

        binding.recyclerCharacters.layoutManager = LinearLayoutManager(this)
        binding.recyclerCharacters.adapter = adapter

        binding.fabAdd.setOnClickListener { showCreateDialog() }

        viewModel.characters.observe(this) { list ->
            adapter.setData(list)
        }

        viewModel.toast.observe(this) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.consumeToast()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun showCreateDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_create_character, null)
        val editName = view.findViewById<TextInputEditText>(R.id.editName)
        val editDesc = view.findViewById<TextInputEditText>(R.id.editDesc)
        val spinnerOrder = view.findViewById<Spinner>(R.id.spinnerOrder)

        val orders = arrayOf("1 阶（最随机）", "2 阶", "3 阶（推荐）", "4 阶（最贴合原文）")
        spinnerOrder.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, orders)
        spinnerOrder.setSelection(2)

        AlertDialog.Builder(this)
            .setTitle("新建角色")
            .setView(view)
            .setPositiveButton("创建") { _, _ ->
                val order = when (spinnerOrder.selectedItemPosition) {
                    0 -> 1; 1 -> 2; 3 -> 4; else -> 3
                }
                viewModel.createCharacter(
                    name = editName.text.toString(),
                    description = editDesc.text.toString(),
                    order = order
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameDialog(c: Character) {
        val edit = TextInputEditText(this)
        edit.setText(c.name)
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                viewModel.renameCharacter(c, edit.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(c: Character) {
        AlertDialog.Builder(this)
            .setTitle("删除角色")
            .setMessage("确定删除「${c.name}」吗？\n该角色的模型、记忆与训练记录将被永久清除。")
            .setPositiveButton("删除") { _, _ -> viewModel.deleteCharacter(c) }
            .setNegativeButton("取消", null)
            .show()
    }
}
