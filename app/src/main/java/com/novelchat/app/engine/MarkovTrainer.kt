package com.novelchat.app.engine

import com.novelchat.app.model.MarkovModel
import java.util.regex.Pattern

/**
 * Markov 链训练器
 *
 * 工作原理：
 * 1. 把小说文本切分为句子
 * 2. 对每句进行分词（中文按字+按词混合）
 * 3. 用 N-gram 方式记录"前驱词 -> 后继词"的统计关系
 * 4. 同步记录对话引导模式（如「XXX道：」「XXX说道」）
 * 5. 维护主题词倒排索引，便于回复时按关键词召回上下文
 *
 * 同时支持对话增量学习：把用户与角色的对话回灌模型，
 * 让角色逐渐习得用户的表达风格并形成长期记忆。
 *
 * 由于采用纯统计 + 内存表，红米 K40 游戏增强版（天玑 1200）
 * 可以在数秒内完成百万字小说训练，无需 GPU。
 */
class MarkovTrainer(
    private val order: Int = 5
) {

    /** 中英文标点句子结束符 */
    private val sentenceEndPattern: Pattern = Pattern.compile("[。！？!?;；\\.]+")

    /** 中文引号对 */
    private val quotePairPattern: Pattern = Pattern.compile("([“\"])([^”\"]*?)([”\"])")

    /** 对话引导词（引号前 / 引号后） */
    private val guidePattern: Pattern = Pattern.compile(
        "([\\u4e00-\\u9fa5A-Za-z]{1,4})\\s*[:：]?\\s*[“\"]"
    )

    /**
     * 训练模型（小说文本）
     * @param rawText 原始小说文本
     * @param model 已存在的模型（增量训练），为 null 则新建
     * @return 训练完成的模型
     */
    fun train(rawText: String, model: MarkovModel? = null): MarkovModel {
        val m = model ?: MarkovModel().also { it.order = order }
        val cleaned = cleanText(rawText)

        // 1. 抽取对话模式
        extractDialoguePatterns(cleaned, m)

        // 2. 切句并训练
        val sentences = splitSentences(cleaned)
        sentences.forEach { sentence ->
            trainSentence(sentence, m, weight = 1, isDialogue = false)
        }

        return m
    }

    /**
     * 增量训练一段用户-角色对话
     *
     * 算法：把"用户问 + 角色答"作为一个对话段落，
     * 把问句与答句一并喂入 N-gram 链，并提升答句的权重，
     * 让角色倾向于在类似问句后给出类似回答（学习记忆）。
     *
     * @param userText 用户输入
     * @param botText 角色回复
     * @param model 已存在的模型
     */
    fun learnFromDialogue(userText: String, botText: String, model: MarkovModel) {
        val userClean = cleanText(userText)
        val botClean = cleanText(botText)
        if (userClean.isBlank() || botClean.isBlank()) return

        // 记录问答记忆（用于硬记忆召回）
        model.rememberQA(userClean.take(64), botClean.take(256))

        // 记录用户词汇分布（用于学习用户风格）
        ChineseTokenizer.tokenize(userClean).forEach { model.addUserWord(it) }

        // 把"问句 + 答句"拼接，作为一段连续对话训练，
        // 使模型学习到"问->答"的过渡。
        val dialogueBlock = "$userClean$botClean"
        val sentences = splitSentences(dialogueBlock)
        sentences.forEachIndexed { idx, sentence ->
            // 答句权重更高，使角色更倾向给出类似回答
            val weight = if (idx == sentences.lastIndex && sentences.size > 1) 3 else 2
            trainSentence(sentence, model, weight = weight, isDialogue = true)
        }

        model.dialogueTrainedChars += userClean.length + botClean.length
    }

    /** 文本清洗 */
    private fun cleanText(raw: String): String = raw
        .replace("\r", "")
        .replace("\u3000", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** 切句：保留对话内部结构 */
    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val matcher = sentenceEndPattern.matcher(text)
        var start = 0
        while (matcher.find()) {
            val end = matcher.end()
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) result.add(sentence)
            start = end
        }
        if (start < text.length) {
            val tail = text.substring(start).trim()
            if (tail.isNotEmpty()) result.add(tail)
        }

        // 对话内含的句子也单独成句，提高对话连贯性
        val dialogues = mutableListOf<String>()
        val qm = quotePairPattern.matcher(text)
        while (qm.find()) {
            val content = qm.group(2) ?: continue
            if (content.length in 2..64) dialogues.add(content)
        }
        result.addAll(dialogues)
        return result
    }

    /** 抽取对话前后引导词，作为风格记忆 */
    private fun extractDialoguePatterns(text: String, m: MarkovModel) {
        val gm = guidePattern.matcher(text)
        while (gm.find()) {
            val lead = gm.group(1) ?: continue
            if (lead.length in 1..4) {
                m.dialoguePrefixes[lead] = (m.dialoguePrefixes[lead] ?: 0) + 1
            }
        }

        val afterQuotePattern = Pattern.compile("[”\"]\\s*([\\u4e00-\\u9fa5]{2,6})")
        val am = afterQuotePattern.matcher(text)
        while (am.find()) {
            val desc = am.group(1) ?: continue
            if (desc.length in 2..6) {
                m.dialogueSuffixes[desc] = (m.dialogueSuffixes[desc] ?: 0) + 1
            }
        }
    }

    /**
     * 训练单句：写入 N-gram 转移表与主题倒排索引
     */
    private fun trainSentence(
        sentence: String,
        m: MarkovModel,
        weight: Int,
        isDialogue: Boolean
    ) {
        if (sentence.length < 2) return
        val tokens = ChineseTokenizer.tokenize(sentence)
        if (tokens.isEmpty()) return

        val padded = ArrayList<String>(tokens.size + order)
        repeat(order - 1) { padded.add(MarkovModel.SENTENCE_END) }
        padded.addAll(tokens)
        padded.add(MarkovModel.SENTENCE_END)

        // 起始词
        m.addStart(tokens[0], weight)
        m.addWord(tokens[0], weight)

        for (i in order - 1 until padded.size - 1) {
            val prefix = (0 until order)
                .map { j -> padded[i - (order - 1) + j] }
                .joinToString(MarkovModel.KEY_DELIMITER)
            val next = padded[i + 1]
            m.addTransition(prefix, next, weight)
            m.addWord(next, weight)
        }

        // 额外记录 bi-gram / tri-gram 搭配频率（用于通顺度评分）
        for (i in 0 until padded.size - 1) {
            val a = padded[i]
            val b = padded[i + 1]
            if (a != MarkovModel.SENTENCE_END && b != MarkovModel.SENTENCE_END) {
                m.addBigram(a, b, weight)
            }
        }
        for (i in 0 until padded.size - 2) {
            val a = padded[i]
            val b = padded[i + 1]
            val c = padded[i + 2]
            if (a != MarkovModel.SENTENCE_END && b != MarkovModel.SENTENCE_END && c != MarkovModel.SENTENCE_END) {
                m.addTrigram(a, b, c, weight)
            }
        }

        // 主题倒排索引：把句子挂到其包含的关键词下
        indexTopics(sentence, m)

        // 结束词
        val last = tokens.last()
        if (last.length == 1 && last.matches(Regex("[。！？!?;；]"))) {
            m.endWords.add(last)
        }

        m.trainedSentences++
        if (!isDialogue) m.trainedChars += sentence.length
    }

    /**
     * 主题索引：把句子挂到其包含的 2-4 字中文词下，便于按关键词召回
     */
    private fun indexTopics(sentence: String, m: MarkovModel) {
        Regex("[\\u4e00-\\u9fa5]{2,4}")
            .findAll(sentence)
            .map { it.value }
            .distinct()
            .take(3)
            .forEach { kw -> m.indexTopicSentence(kw, sentence.take(80)) }
    }
}
