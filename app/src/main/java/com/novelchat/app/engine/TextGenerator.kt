package com.novelchat.app.engine

import com.novelchat.app.model.MarkovModel
import kotlin.random.Random

/**
 * 文本/对话生成器（增强版）
 *
 * 深度对话能力来源：
 * 1. 上下文回溯：把最近 N 轮用户输入与角色回复作为前驱种子，让回复与历史连贯
 * 2. 主题召回：从用户输入中提取关键词，从 topicIndex 召回相关原句作为回复骨架
 * 3. 问答记忆：若曾回答过相似问题，直接复用历史回复（硬记忆）
 * 4. 多候选打分：生成多个候选回复，按"与用户输入共现词数 + 长度合理性"打分择优
 * 5. 风格包装：根据小说对话前后缀模式，用引号 + 引导词包装回复
 */
class TextGenerator(
    private val model: MarkovModel,
    private val memory: DialogueMemory? = null,
    private val random: Random = Random.Default
) {

    /** 最大生成长度 */
    var maxLength: Int = 80

    /** 采样温度（>1 更随机，<1 更确定） */
    var temperature: Float = 0.85f

    /** 是否用引号包裹（启用对话风格） */
    var wrapWithQuote: Boolean = true

    /** 候选数量（多候选打分） */
    var candidates: Int = 4

    /** 上下文回溯窗口（最近几轮） */
    var contextTurns: Int = 2

    /**
     * 根据用户输入生成回复
     */
    fun reply(userInput: String): String {
        if (!model.isReady()) {
            return "尚未完成训练，请先导入 TXT 小说并训练模型。"
        }

        // 1) 硬记忆：若问过几乎相同的问题，直接返回历史回复
        memory?.let { mem ->
            val recalled = recallFromQAMemory(userInput)
            if (recalled != null) return recalled
        }

        // 2) 多候选打分
        val seed = buildSeed(userInput)
        var candidateList = (1..candidates).map { generate(seed) }

        // 若种子模式全部失败（如用户末词不在模型中或立刻遇到句号），
        // 回退到无种子模式（用起始词作为开头）
        if (candidateList.all { it.isBlank() }) {
            candidateList = (1..candidates).map { generate(null) }
        }

        // 3) 主题召回：从 topicIndex 找一段与用户输入最相关的原句
        val recalledSentence = recallByTopic(userInput)

        // 4) 打分择优
        val best = pickBest(candidateList, recalledSentence, userInput)
            .takeIf { it.isNotBlank() }
            ?: (recalledSentence ?: "")

        // 5) 包装回复
        return polishReply(best, userInput)
    }

    /**
     * 从问答记忆硬召回（精确匹配或包含匹配）
     */
    private fun recallFromQAMemory(userInput: String): String? {
        if (model.qaMemory.isEmpty()) return null
        val cleaned = userInput.trim()
        // 精确匹配
        model.qaMemory[cleaned]?.let { answers ->
            if (answers.isNotEmpty()) return answers.random(random)
        }
        // 包含匹配：用户输入与某个记忆问句互相包含
        model.qaMemory.keys.firstOrNull { key ->
            key.length > 3 && (key.contains(cleaned) || cleaned.contains(key))
        }?.let { key ->
            val answers = model.qaMemory[key] ?: return null
            if (answers.isNotEmpty()) return answers.random(random)
        }
        return null
    }

    /**
     * 构造续写种子：融合用户输入末尾词 + 最近几轮上下文
     */
    private fun buildSeed(userInput: String): List<String> {
        val seed = mutableListOf<String>()

        // 加入最近几轮的"用户末词"作为上下文锚点
        memory?.let { mem ->
            mem.recentUserInputs(contextTurns).forEach { histInput ->
                val toks = ChineseTokenizer.tokenize(histInput)
                if (toks.isNotEmpty()) seed.add(toks.last())
            }
            mem.recentBotReplies(contextTurns).forEach { histReply ->
                val toks = ChineseTokenizer.tokenize(histReply)
                if (toks.isNotEmpty()) seed.add(toks.last())
            }
        }

        // 加入当前用户输入的末词
        val userTokens = ChineseTokenizer.tokenize(userInput.trim())
        if (userTokens.isNotEmpty()) {
            seed.add(userTokens.last())
        }

        // 过滤掉模型中不存在的词，避免无效种子
        val filtered = seed.filter { model.vocabulary.containsKey(it) }
        return if (filtered.isEmpty()) seed.distinct() else filtered.distinct()
    }

    /**
     * 生成一段文本
     * @param seed 起始词列表（作为前驱上下文，不会被回显）
     */
    private fun generate(seed: List<String>?): String {
        val buffer = StringBuilder()
        val current = ArrayDeque<String>()
        val prefixLen = model.order.coerceAtLeast(1)

        if (seed != null && seed.isNotEmpty()) {
            seed.forEach { current.addLast(it) }
            while (current.size < prefixLen) {
                current.addFirst(MarkovModel.SENTENCE_END)
            }
            while (current.size > prefixLen) {
                current.removeFirst()
            }
        } else {
            val startWord = weightedPick(model.startWords) ?: return ""
            buffer.append(startWord)
            repeat(prefixLen - 1) { current.addLast(MarkovModel.SENTENCE_END) }
            current.addLast(startWord)
        }

        var steps = 0
        while (steps < maxLength) {
            val prefix = current.toList()
                .takeLast(prefixLen)
                .joinToString(MarkovModel.KEY_DELIMITER)

            val next = sampleNext(prefix)
            if (next == null || next == MarkovModel.SENTENCE_END) break

            buffer.append(next)
            current.addLast(next)
            if (current.size > prefixLen) current.removeFirst()

            if (next.length == 1 && next.matches(Regex("[。！？!?;；]"))) break

            steps++
        }

        return buffer.toString()
    }

    /**
     * 加权采样下一个词（带回退）
     */
    private fun sampleNext(prefix: String): String? {
        val bucket = model.forwardChain[prefix]
        if (bucket.isNullOrEmpty()) {
            // 回退：低阶 N-gram
            val parts = prefix.split(MarkovModel.KEY_DELIMITER)
            if (parts.size > 1) {
                val lowerKey = parts.drop(parts.size - (model.order - 1).coerceAtLeast(1))
                    .joinToString(MarkovModel.KEY_DELIMITER)
                model.forwardChain[lowerKey]?.let { return weightedPick(it) }
            }
            return weightedPick(model.vocabulary)
        }
        return weightedPick(bucket)
    }

    /**
     * 带温度的加权随机选择
     */
    private fun weightedPick(weights: Map<String, Int>): String? {
        if (weights.isEmpty()) return null
        val items = weights.entries.toList()
        val total: Double = items.sumOf { entry ->
            val w = entry.value.toDouble()
            if (temperature == 1.0f) w
            else Math.pow(w, 1.0 / temperature)
        }
        if (total <= 0.0) return items.first().key

        var r = random.nextDouble(total)
        for (entry in items) {
            val w = if (temperature == 1.0f) entry.value.toDouble()
            else Math.pow(entry.value.toDouble(), 1.0 / temperature)
            r -= w
            if (r <= 0) return entry.key
        }
        return items.last().key
    }

    /**
     * 从主题倒排索引召回与用户输入最相关的原句
     */
    private fun recallByTopic(userInput: String): String? {
        if (model.topicIndex.isEmpty()) return null
        val keywords = Regex("[\\u4e00-\\u9fa5]{2,4}")
            .findAll(userInput)
            .map { it.value }
            .distinct()
            .toList()
        if (keywords.isEmpty()) return null

        // 收集所有候选句并打分：关键词命中数越多越好
        val scored = mutableListOf<Pair<String, Int>>()
        keywords.forEach { kw ->
            model.topicIndex[kw]?.forEach { sentence ->
                val score = keywords.count { sentence.contains(it) }
                scored.add(sentence to score)
            }
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.second }
        return scored.first().first
    }

    /**
     * 在多个候选回复中择优
     *
     * 评分维度：
     *  + 与用户输入共现词数（切题度）
     *  + 与召回句相似度
     *  - 长度过短或过长（合理性）
     *  + 多样性（避免与上一轮回复完全相同）
     */
    private fun pickBest(
        candidates: List<String>,
        recalled: String?,
        userInput: String
    ): String {
        val userTokens = ChineseTokenizer.tokenize(userInput).toSet()
        val lastReply = memory?.recentBotReplies(1)?.lastOrNull() ?: ""

        val scored = candidates.map { cand ->
            var score = 0
            val candTokens = ChineseTokenizer.tokenize(cand).toSet()
            // 切题度
            score += (userTokens intersect candTokens).size * 3
            // 召回句相似度
            recalled?.let { rc ->
                val rcTokens = ChineseTokenizer.tokenize(rc).toSet()
                score += (rcTokens intersect candTokens).size * 2
            }
            // 长度合理性
            val len = cand.length
            when {
                len in 6..60 -> score += 5
                len in 2..80 -> score += 1
                else -> score -= 5
            }
            // 多样性
            if (cand == lastReply) score -= 8
            cand to score
        }
        val sorted = scored.sortedByDescending { pair: Pair<String, Int> -> pair.second }
        return sorted.firstOrNull()?.first ?: ""
    }

    /**
     * 后处理回复：保证完整性 + 对话包装
     */
    private fun polishReply(rawSentence: String, userInput: String): String {
        var text = rawSentence.trim()
        if (text.isEmpty()) {
            val startWord = weightedPick(model.startWords)
            text = startWord ?: "……"
        }
        // 补全末尾标点
        if (text.isNotEmpty() && !text.last().toString().matches(Regex("[。！？!?;；…\"]"))) {
            text += "。"
        }

        if (wrapWithQuote && model.dialoguePrefixes.isNotEmpty()) {
            // 40% 概率包装为对话形式
            if (random.nextInt(100) < 40) {
                text = "“$text”"
            }
        }
        return text
    }
}
