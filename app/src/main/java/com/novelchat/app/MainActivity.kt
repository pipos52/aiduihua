package com.novelchat.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelchat.app.databinding.ActivityMainBinding
import com.novelchat.app.ui.ChatAdapter
import com.novelchat.app.ui.MainViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val chatAdapter = ChatAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val characterId = intent.getStringExtra("character_id")
        val trainUriStr = intent.getStringExtra("train_uri")
        val trainFileName = intent.getStringExtra("train_file_name")

        viewModel.init(applicationContext, characterId)

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()

        // 若从角色列表带着训练 Uri 进入，立即开始训练
        if (!trainUriStr.isNullOrEmpty()) {
            viewModel.trainFromUri(Uri.parse(trainUriStr), trainFileName ?: "未命名.txt")
        }
    }

    private fun setupRecyclerView() {
        binding.recyclerChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerChat.adapter = chatAdapter
    }

    private fun setupClickListeners() {
        // 切换角色：返回角色列表
        binding.btnImport.setOnClickListener {
            startActivity(Intent(this, CharacterListActivity::class.java))
        }

        binding.btnMenu.setOnClickListener { view ->
            showPopupMenu(view)
        }

        binding.btnSend.setOnClickListener {
            val text = binding.editInput.text.toString()
            if (text.isNotBlank()) {
                viewModel.sendMessage(text)
                binding.editInput.text?.clear()
            }
        }
    }

    private fun showPopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "切换角色")
        popup.menu.add(0, 2, 0, "清空对话")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { startActivity(Intent(this, CharacterListActivity::class.java)); true }
                2 -> { viewModel.clearChat(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun observeViewModel() {
        viewModel.character.observe(this) { c ->
            binding.tvTitle.text = c?.name ?: "小说文风聊天"
        }
        viewModel.statusText.observe(this) { value ->
            binding.tvStatus.text = value
        }
        viewModel.progress.observe(this) { value ->
            binding.progressBar.progress = value
        }
        viewModel.progressVisible.observe(this) { visible ->
            binding.progressBar.visibility =
                if (visible) View.VISIBLE else View.GONE
        }
        viewModel.messages.observe(this) { messages ->
            chatAdapter.submit(messages)
            if (messages.isNotEmpty()) {
                binding.recyclerChat.scrollToPosition(messages.size - 1)
            }
        }
        viewModel.toast.observe(this) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.consumeToast()
            }
        }
    }
}
