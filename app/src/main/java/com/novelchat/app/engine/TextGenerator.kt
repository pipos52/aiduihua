package com.novelchat.app.engine

import com.novelchat.app.model.MarkovModel
import kotlin.random.Random

/**
 * 文本/对话生成器（增强通顺版）
 *
 * 让生成的中文回复更通顺的核心改动：
 * 1. 低温度采样（0.55）：优先选择高频搭配，拒绝"奇怪字组合"
 * 2. 8 个候选择优：多生成几个，用 bi-gram / tri-gram 真实出现过的搭配来打分
 * 3. 通顺度硬约束：生成的每个连续双字/三字必须在训练语料中至少见过一次，否则扣分甚至淘汰
 * 4. 种子从"用户末词"升级为"用户最后 N 个 token"，作为 N-gram 前缀
 * 5. 采样最后一步回退不再从 vocabulary 乱选，改为从 startWords + 词频 Top-N 保守选择
 * 6. 句尾强约束：在最后约 25% 生成步数强制提升句号/感叹号的采样权重，保证以完整句子结尾
 */
class TextGenerator(
    private val model: MarkovModel,
    private val memory: DialogueMemory? = null,
    private val random: Random = Random.Default
) {

    /** 最大生成长度 */
    var maxLength: Int = 90

    /** 采样温度 — 降低到 0.55，减少随机性、提升通顺度 */
    var temperature: Float = 0.55f

    /** 是否用引号包裹（启用对话风格） */
    var wrapWithQuote: Boolean = true

    /** 候选数量：8 个候选择优，比 4 个更有概率挑到通顺句子 */
    var candidates: Int = 8

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
        memory?.let {
            val recalled = recallFromQAMemory(userInput)
            if (recalled != null) return recalled
        }

        // 2) 构建种子前缀：取用户输入最后 N 个字，作为马尔可夫链的起点
        val seedPrefix = buildSeedPrefix(userInput)

        // 3) 生成多组候选
        var candidateList = (1..candidates).map { generate(seedPrefix) }

        // 如果种子模式全部产出空白，回退到无种子模式
        if (candidateList.all { it.isBlank() }) {
            candidateList = (1..candidates).map { generate(null) }
        }

        // 4) 召回一句与用户输入最相关的原句，并把它也作为候选（原文一般最通顺）
        val recalledSentence = recallByTopic(userInput)
        val allCandidates = if (recalledSentence != null) {
            candidateList + recalledSentence
        } else candidateList

        // 5) 打分择优（含通顺度评分）
        val best = pickBest(allCandidates, recalledSentence, userInput)
            .takeIf { it.isNotBlank() }
            ?: (recalledSentence ?: "")

        // 6) 后处理：保证完整性 + 对话包装 + 去重复字符
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
        // 包含匹配
        model.qaMemory.keys.firstOrNull { key ->
            key.length > 3 && (key.contains(cleaned) || cleaned.contains(key))
        }?.let { key ->
            val answers = model.qaMemory[key] ?: return null
            if (answers.isNotEmpty()) return answers.random(random)
        }
        return null
    }

    /**
     * 构造 N-gram 前缀种子：取用户输入最后的 (order - 1) 个 token
     * 这样马尔可夫链从与用户输入真正"相邻"的位置开始续写，
     * 回复的开头会自然得多，不再是莫名其妙的字拼接。
     */
    private fun buildSeedPrefix(userInput: String): List<String>? {
        val prefixLen = (model.order - 1).coerceAtLeast(1)
        val all = mutableListOf<String>()

        // 最近几轮上下文的结尾 token（贡献前半段前缀）
        memory?.let { mem ->
            mem.recentBotReplies(contextTurns).forEach { reply ->
                val toks = ChineseTokenizer.tokenize(reply)
                if (toks.isNotEmpty()) all.add(toks.last())
            }
            mem.recentUserInputs(contextTurns).forEach { hist ->
                val toks = ChineseTokenizer.tokenize(hist)
                if (toks.isNotEmpty()) all.add(toks.last())
            }
        }

        // 当前用户输入的最后若干 token（权重最高）
        val userTokens = ChineseTokenizer.tokenize(userInput.trim())
        if (userTokens.isNotEmpty()) {
            val tail = userTokens.takeLast(prefixLen)
            all.addAll(tail)
        }

        if (all.isEmpty()) return null

        // 只保留词表里出现过的，最后裁剪为 prefixLen 长度
        val vocab = model.vocabulary.keys
        val valid = all.filter { it in vocab }
        val combined = valid.ifEmpty { all.distinct() }
        return combined.takeLast(prefixLen)
    }

    /**
     * 生成一段文本（采样阶段即尽量避免不通顺组合）
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
            val prefixKey = current.toList()
                .takeLast(prefixLen)
                .joinToString(MarkovModel.KEY_DELIMITER)

            val next = sampleNext(
                prefix = prefixKey,
                lastToken = current.lastOrNull() ?: "",
                forceEndBias = (steps >= maxLength * 0.75) // 进入末尾：提升句末标点概率
            )
            if (next == null || next == MarkovModel.SENTENCE_END) break

            buffer.append(next)
            current.addLast(next)
            if (current.size > prefixLen) current.removeFirst()

            // 遇到句号类标点 → 自然结束（保证整句完整）
            if (next.length == 1 && next.matches(Regex("[。！？!?;；]"))) break

            steps++
        }

        return buffer.toString()
    }

    /**
     * 加权采样下一个词（带回退 + 通顺度偏置 + 末尾强制标点）
     */
    private fun sampleNext(
        prefix: String,
        lastToken: String,
        forceEndBias: Boolean
    ): String? {
        var bucket = model.forwardChain[prefix]

        // 回退策略：逐阶降低 N-gram 阶数，但绝不用「从 vocabulary 随机选」
        var localPrefix = prefix
        while (bucket.isNullOrEmpty()) {
            val parts = localPrefix.split(MarkovModel.KEY_DELIMITER)
            if (parts.size <= 1) break
            localPrefix = parts.drop(1).joinToString(MarkovModel.KEY_DELIMITER)
            bucket = model.forwardChain[localPrefix]
        }

        // 如果仍然没有 bucket，就从起始词中保守选（不要从 vocabulary 乱抽）
        val weights: Map<String, Int> = if (bucket.isNullOrEmpty()) {
            // 保守回退：优先 startWords，其次是词频 Top 100 里随机几个，
            // 这样不会冒出奇怪的罕见字组合
            val fallback = model.startWords
            if (fallback.isNotEmpty()) fallback else model.vocabulary.entries
                .sortedByDescending { it.value }
                .take(100)
                .associate { it.key to it.value }
        } else bucket

        // ---- 通顺度偏置：对「与 lastToken 从未见过的 bi-gram」降权 ----
        val biased = if (lastToken.isNotEmpty() && lastToken != MarkovModel.SENTENCE_END) {
            val map = LinkedHashMap<String, Double>(weights.size)
            for ((k, v) in weights) {
                var w = v.toDouble()
                // 这个双字搭配没见过 → 权重砍到 1%（几乎不会被抽到）
                if (!model.hasBigram(lastToken, k)) w *= 0.01
                // 末尾强制句末标点
                if (forceEndBias && k.length == 1 && k in setOf("。", "！", "？", "!", "?")) {
                    w *= 8.0
                }
                // 结束词也额外提升
                if (forceEndBias && model.endWords.contains(k)) w *= 4.0
                if (w > 0) map[k] = w
            }
            map
        } else {
            weights.mapValues { it.value.toDouble() }
        }

        return weightedPickDouble(biased)
    }

    private fun weightedPick(weights: Map<String, Int>): String? =
        weightedPickDouble(weights.mapValues { it.value.toDouble() })

    /**
     * 带温度的加权随机选择（接受 Double 权重，适配通顺度偏置后的浮点权重）
     */
    private fun weightedPickDouble(weights: Map<String, Double>): String? {
        if (weights.isEmpty()) return null
        val items = weights.entries.toList()
        val total: Double = items.sumOf { entry ->
            val w = entry.value
            if (temperature == 1.0f) w
            else Math.pow(w.coerceAtLeast(0.0), 1.0 / temperature)
        }
        if (total <= 0.0) {
            // 全部被通顺度惩罚置 0 → 选权重最高的那个保底
            return items.maxByOrNull { it.value }?.key
        }

        var r = random.nextDouble(total)
        for (entry in items) {
            val w = if (temperature == 1.0f) entry.value
            else Math.pow(entry.value.coerceAtLeast(0.0), 1.0 / temperature)
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

        val scored = mutableListOf<Pair<String, Int>>()
        keywords.forEach { kw ->
            model.topicIndex[kw]?.forEach { sentence ->
                val score = keywords.count { sentence.contains(it) }
                scored.add(sentence to score)
            }
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.second }
        // 优先返回完整、长度合适的句子
        return scored.asSequence()
            .map { it.first }
            .firstOrNull { it.length in 4..80 }
            ?: scored.first().first
    }

    /**
     * 在多个候选回复中择优（新增通顺度评分维度）
     */
    private fun pickBest(
        candidates: List<String>,
        recalled: String?,
        userInput: String
    ): String {
        val userTokens = ChineseTokenizer.tokenize(userInput).toSet()
        val lastReply = memory?.recentBotReplies(1)?.lastOrNull() ?: ""

        val scored = candidates.map { cand ->
            val candTokens = ChineseTokenizer.tokenize(cand)
            val candSet = candTokens.toSet()

            var score = 0.0

            // === 1) 切题度：与用户输入共现词数 ===
            score += (userTokens intersect candSet).size * 3.0

            // === 2) 与召回句相似度 ===
            recalled?.let { rc ->
                val rcTokens = ChineseTokenizer.tokenize(rc).toSet()
                score += (rcTokens intersect candSet).size * 2.0
            }

            // === 3) 通顺度核心：逐双字/三字检查是否在训练中出现过 ===
            var bigramHit = 0
            var bigramMiss = 0
            var trigramHit = 0
            var trigramMiss = 0
            for (i in 0 until candTokens.size - 1) {
                val a = candTokens[i]
                val b = candTokens[i + 1]
                if (model.hasBigram(a, b)) bigramHit++ else bigramMiss++
            }
            for (i in 0 until candTokens.size - 2) {
                val a = candTokens[i]
                val b = candTokens[i + 1]
                val c = candTokens[i + 2]
                if (model.hasTrigram(a, b, c)) trigramHit++ else trigramMiss++
            }
            val totalBi = (bigramHit + bigramMiss).coerceAtLeast(1)
            val totalTri = (trigramHit + trigramMiss).coerceAtLeast(1)
            score += (bigramHit.toDouble() / totalBi) * 25.0   // 双字命中率高 → 大加分
            score += (trigramHit.toDouble() / totalTri) * 20.0  // 三字命中率高 → 更大加分
            score -= bigramMiss * 1.2                            // 每个未见过的双字 → 扣分
            score -= trigramMiss * 0.8                           // 每个未见过的三字 → 额外扣分

            // === 4) 长度合理性（中文对话 8~50 字最自然） ===
            val len = cand.length
            when {
                len in 8..50 -> score += 10.0
                len in 4..80 -> score += 3.0
                else -> score -= 8.0
            }

            // === 5) 结尾标点：以句号/感叹/问号结尾的句子更完整 → 加分 ===
            if (cand.isNotEmpty() && cand.last().toString()
                    .matches(Regex("[。！？!?;；]"))) score += 6.0

            // === 6) 拒绝疯狂重复：比如"哈哈哈哈哈哈哈哈"或同字连续出现 5 次以上 ===
            if (hasExcessiveRepeat(cand)) score -= 15.0

            // === 7) 开头避免直接是标点 ===
            val firstChar = cand.firstOrNull()?.toString() ?: ""
            if (firstChar.matches(Regex("[，。！？!?、；：,.:;]"))) score -= 5.0

            // === 8) 多样性：不要跟上一轮回复一模一样 ===
            if (cand == lastReply) score -= 10.0

            cand to score
        }

        val sorted = scored.sortedByDescending { pair: Pair<String, Double> -> pair.second }
        return sorted.firstOrNull()?.first ?: ""
    }

    /** 连续重复字符超过阈值（默认 5 个）就算"过度重复" */
    private fun hasExcessiveRepeat(text: String, threshold: Int = 5): Boolean {
        if (text.length < threshold) return false
        var count = 1
        for (i in 1 until text.length) {
            if (text[i] == text[i - 1]) {
                count++
                if (count >= threshold) return true
            } else {
                count = 1
            }
        }
        return false
    }

    /**
     * 后处理回复：保证完整性 + 对话包装 + 清理不通顺尾段
     */
    private fun polishReply(rawSentence: String, userInput: String): String {
        var text = rawSentence.trim()

        // 空回退：优先用召回句，再次用 startWords 高频词开头的一句话随机，
        // 再不济就返回一句自然的兜底话
        if (text.isEmpty()) {
            val startWord = weightedPick(model.startWords)
            text = if (startWord != null) {
                val extra = generate(listOf(startWord))
                (startWord + extra).trim()
            } else ""
            if (text.isEmpty()) {
                text = if (userInput.isNotBlank()) "嗯。" else "……"
            }
        }

        // 截断：如果句末标点在中间位置，就只保留到第一个完整句末标点
        val idxEnd = text.indexOfAny(charArrayOf('。', '！', '？', '!', '?', '；', ';'))
        if (idxEnd >= 0 && idxEnd < text.length - 1) {
            text = text.substring(0, idxEnd + 1)
        }

        // 去掉连续重复字符（如「哈哈哈哈哈哈哈」压缩到 4 个）
        text = collapseRepeats(text, 4)

        // 补全末尾标点
        if (text.isNotEmpty() && !text.last().toString()
                .matches(Regex("[。！？!?;；…\"”]"))) {
            text += "。"
        }

        // 去掉开头标点
        while (text.isNotEmpty() && text.first().toString()
                .matches(Regex("[，。！？!?、；：,.:;]"))) {
            text = text.substring(1)
        }

        // 对话风格包装
        if (wrapWithQuote && model.dialoguePrefixes.isNotEmpty()) {
            if (random.nextInt(100) < 55) {
                text = "“$text”"
            }
        }
        return text.ifEmpty { "嗯。" }
    }

    /** 把连续重复字符截断到 maxCount 个（不影响标点） */
    private fun collapseRepeats(text: String, maxCount: Int): String {
        if (text.length <= maxCount) return text
        val sb = StringBuilder(text.length)
        var count = 1
        sb.append(text[0])
        for (i in 1 until text.length) {
            if (text[i] == text[i - 1] && text[i].code in 0x4E00..0x9FFF) {
                count++
                if (count <= maxCount) sb.append(text[i])
            } else {
                count = 1
                sb.append(text[i])
            }
        }
        return sb.toString()
    }
}
