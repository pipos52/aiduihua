package com.novelchat.app.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.novelchat.app.MainActivity
import com.novelchat.app.R
import com.novelchat.app.model.Character

/**
 * 角色列表适配器
 */
class CharacterAdapter(
    private val characters: MutableList<Character>,
    private val onTrainClick: (Character) -> Unit,
    private val onDeleteClick: (Character) -> Unit,
    private val onRenameClick: (Character) -> Unit
) : RecyclerView.Adapter<CharacterAdapter.VH>() {

    fun setData(list: List<Character>) {
        characters.clear()
        characters.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_character, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(characters[position])
    }

    override fun getItemCount(): Int = characters.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvDesc: TextView = itemView.findViewById(R.id.tvDesc)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

        fun bind(c: Character) {
            tvName.text = c.name
            val filesTxt = if (c.trainedFiles.isEmpty()) "未训练"
            else "已训练 ${c.trainedFiles.size} 本小说"
            tvDesc.text = "$filesTxt · ${c.trainedChars} 字 / ${c.trainedSentences} 句" +
                    if (c.dialogueTurns > 0) " · 对话 ${c.dialogueTurns} 轮" else ""

            itemView.setOnClickListener {
                val ctx = itemView.context
                val intent = Intent(ctx, MainActivity::class.java).apply {
                    putExtra("character_id", c.id)
                }
                ctx.startActivity(intent)
            }

            btnMore.setOnClickListener { anchor ->
                val popup = PopupMenu(itemView.context, anchor)
                popup.menu.add(0, 1, 0, "继续训练（导入TXT）")
                popup.menu.add(0, 2, 0, "重命名")
                popup.menu.add(0, 3, 0, "删除")
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { onTrainClick(c); true }
                        2 -> { onRenameClick(c); true }
                        3 -> { onDeleteClick(c); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }
}
