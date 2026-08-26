package com.novelchat.app.engine

/**
 * 中文混合分词器
 *
 * 策略：
 *  - 中文：按字为单位（便于捕捉风格）
 *  - 英文 / 数字：连续字母数字整体作为一词
 *  - 标点：单独成 token
 */
object ChineseTokenizer {

    fun tokenize(sentence: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var lastType = 0 // 0=other, 1=chinese, 2=alphanumeric

        fun flush() {
            if (sb.isNotEmpty()) {
                tokens.add(sb.toString())
                sb.setLength(0)
            }
        }

        for (ch in sentence) {
            val type = when {
                ch in ' '..'~' && ch.isLetterOrDigit() -> 2
                ch.code in 0x4E00..0x9FFF -> 1
                else -> 0
            }

            when {
                type == 2 && lastType == 2 -> sb.append(ch)
                type == 1 -> {
                    flush()
                    sb.append(ch)
                    flush()
                }
                else -> {
                    flush()
                    if (type == 2) sb.append(ch)
                    else if (ch != ' ') tokens.add(ch.toString())
                }
            }
            lastType = type
        }
        flush()
        return tokens
    }
}
