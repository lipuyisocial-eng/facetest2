package com.xingyihui.interviewassistant.diag

import android.content.Context

/**
 * 内置面试知识库（assets/kb 下的 txt，由用户提供的 CC014 语料全量清洗后生成）。
 * 只作「方法论 / 话术框架」参考，不得当作候选人真实经历数据使用（上传的项目资料优先）。
 */
object BuiltinKnowledge {

    private const val CHUNK = 320
    private const val OVERLAP = 60
    private const val MAX_CHARS = 1000

    private var loaded = false
    private val topics = ArrayList<Pair<String, String>>() // 文件名 -> 全文

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        try {
            val files = ctx.assets.list("kb")?.sorted().orEmpty()
            for (f in files) {
                try {
                    val text = ctx.assets.open("kb/$f").bufferedReader().use { it.readText() }
                    if (text.isNotBlank()) topics.add(f to text)
                } catch (_: Exception) {
                }
            }
            loaded = true
            android.util.Log.i("InterviewApp", "内置知识库加载: ${topics.size} 个主题")
        } catch (e: Exception) {
            android.util.Log.i("InterviewApp", "内置知识库加载失败: ${e.message}")
        }
    }

    fun topicCount(): Int = topics.size

    fun totalChars(): Int = topics.sumOf { it.second.length }

    /** 按问题检索内置知识库，返回带标签的参考片段（≤ maxChars） */
    fun retrieve(ctx: Context, question: String, maxChars: Int = MAX_CHARS): String {
        ensureLoaded(ctx)
        if (topics.isEmpty() || question.isBlank()) return ""
        val q = question.filter { it.isLetterOrDigit() }
        if (q.length < 2) return ""
        data class Hit(val score: Double, val topic: String, val text: String)
        val hits = ArrayList<Hit>()
        for ((name, body) in topics) {
            var start = 0
            while (start < body.length) {
                val end = minOf(start + CHUNK, body.length)
                val chunk = body.substring(start, end)
                val sc = score(q, chunk)
                if (sc > 0.22) hits.add(Hit(sc, name, chunk))
                if (end >= body.length) break
                start += CHUNK - OVERLAP
            }
        }
        if (hits.isEmpty()) return ""
        val sb = StringBuilder("\n\n【内置面试知识库（方法论参考，禁止当作候选人真实经历或数字；用户上传资料优先）】\n")
        var used = 0
        for (h in hits.sortedByDescending { it.score }.take(2)) {
            val piece = "〔${h.topic.removeSuffix(".txt")}〕${h.text.trim()}\n"
            if (used + piece.length > maxChars) break
            sb.append(piece)
            used += piece.length
        }
        return sb.toString()
    }

    private fun score(query: String, chunk: String): Double {
        val q = query.filter { it.isLetterOrDigit() }
        val c = chunk.filter { it.isLetterOrDigit() }
        if (q.isEmpty() || c.isEmpty()) return 0.0
        val pool = c.toMutableList()
        var single = 0
        for (ch in q) {
            val i = pool.indexOf(ch)
            if (i >= 0) { single++; pool.removeAt(i) }
        }
        var bigram = 0
        var total = 0
        var i = 0
        while (i + 1 < q.length) {
            total++
            if (c.contains(q.substring(i, i + 2))) bigram++
            i++
        }
        val singleRate = single.toDouble() / q.length
        val bigramRate = if (total > 0) bigram.toDouble() / total else 0.0
        return 0.4 * singleRate + 0.6 * bigramRate
    }
}
