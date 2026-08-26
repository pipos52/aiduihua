package com.novelchat.app.model

import java.io.Serializable

/**
 * Markov 链模型数据结构
 *
 * 存储训练得到的词语转移概率与对话模式。
 * 支持用户对话增量学习：用户与角色互动的对话会被回灌到模型，
 * 让角色逐渐习得用户的说话风格，并形成长期记忆。
 */
class MarkovModel : Serializable {

    /**
     * 主链：前驱词列表 -> 后继词出现次数
     * key 由 N 个前驱词用 \u0001 连接而成
     */
    val forwardChain: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()

    /**
     * 起始词分布（每句话开头词的出现次数）
     */
    val startWords: MutableMap<String, Int> = mutableMapOf()

    /**
     * 句子结束词集合（用于控制生成长度）
     */
    val endWords: MutableSet<String> = mutableSetOf()

    /**
     * 对话前缀模式：引号前出现的引导词（如 "说道"、"道"、"曰"）
     */
    val dialoguePrefixes: MutableMap<String, Int> = mutableMapOf()

    /**
     * 对话后缀模式：引号后跟随的描述词
     */
    val dialogueSuffixes: MutableMap<String, Int> = mutableMapOf()

    /**
     * 引号字符集合（中文 / 英文引号）
     */
    val quoteChars: List<Char> = listOf('"', '"', '"', '"', '\'', '\'')

    /**
     * 词汇总频次（用于回退采样）
     */
    val vocabulary: MutableMap<String, Int> = mutableMapOf()

    /**
     * 用户词汇频次：记录用户在与角色对话中使用的词汇分布，
     * 用于让角色"学习"用户的表达习惯（增量训练时填充）。
     */
    val userVocabulary: MutableMap<String, Int> = mutableMapOf()

    /**
     * 问句模板：用户问句 -> 角色曾给出的回复（仅保留高分回复作为记忆锚点）
     */
    val qaMemory: MutableMap<String, MutableList<String>> = mutableMapOf()

    /**
     * 主题词倒排索引：词 -> 出现该词的句子（截断保存，最多 5 句）。
     * 用于在用户输入中识别到关键词时，召回相关上下文进行续写，
     * 让回复更"切题"。
     */
    val topicIndex: MutableMap<String, MutableList<String>> = mutableMapOf()

    /** 模型阶数 N（N-gram） */
    var order: Int = 3

    /** 已训练的总句数 */
    var trainedSentences: Int = 0

    /** 训练源字数 */
    var trainedChars: Int = 0

    /** 来自用户对话训练的字数 */
    var dialogueTrainedChars: Int = 0

    /** 模型是否已就绪 */
    fun isReady(): Boolean = forwardChain.isNotEmpty() && startWords.isNotEmpty()

    /** 增加一次前驱->后继的转移 */
    fun addTransition(prefixKey: String, next: String, weight: Int = 1) {
        val bucket = forwardChain.getOrPut(prefixKey) { mutableMapOf() }
        bucket[next] = (bucket[next] ?: 0) + weight
    }

    /** 增加一次起始词 */
    fun addStart(word: String, weight: Int = 1) {
        startWords[word] = (startWords[word] ?: 0) + weight
    }

    /** 增加词汇频次 */
    fun addWord(word: String, weight: Int = 1) {
        vocabulary[word] = (vocabulary[word] ?: 0) + weight
    }

    /** 增加用户词汇（用于风格学习） */
    fun addUserWord(word: String, weight: Int = 1) {
        userVocabulary[word] = (userVocabulary[word] ?: 0) + weight
    }

    /** 把句子加入主题倒排索引 */
    fun indexTopicSentence(keyWord: String, sentence: String) {
        val list = topicIndex.getOrPut(keyWord) { mutableListOf() }
        if (list.size < 5 && !list.contains(sentence)) {
            list.add(sentence)
        }
    }

    /** 记录一次问答记忆 */
    fun rememberQA(question: String, answer: String) {
        val list = qaMemory.getOrPut(question) { mutableListOf() }
        if (list.size < 3) list.add(answer)
    }

    companion object {
        private const val serialVersionUID: Long = 20240103L
        const val KEY_DELIMITER = "\u0001"
        const val SENTENCE_END = "<END>"
    }
}
