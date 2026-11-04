package com.xingyihui.interviewassistant.diag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * F2 本地知识库：解析后的资料存 filesDir/kb/<id>.txt，索引 index.json。
 * 检索用「字符二元组 + 单字覆盖」打分（中文无需分词），返回 topN 片段拼给话术 prompt。
 */
object DocumentStore {

    private const val CHUNK = 320
    private const val OVERLAP = 60
    private const val MAX_CHARS = 1400

    data class Doc(val id: String, val name: String, val type: String, val chars: Int, val addedAt: Long)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "kb").apply { if (!exists()) mkdirs() }
    private fun indexFile(ctx: Context): File = File(dir(ctx), "index.json")
    private fun textFile(ctx: Context, id: String): File = File(dir(ctx), "$id.txt")

    fun list(ctx: Context): List<Doc> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Doc(o.optString("id"), o.optString("name"), o.optString("type"),
                    o.optInt("chars"), o.optLong("addedAt"))
            }.sortedByDescending { it.addedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(ctx: Context, docs: List<Doc>) {
        val arr = JSONArray()
        docs.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("type", d.type)
                put("chars", d.chars); put("addedAt", d.addedAt)
            })
        }
        indexFile(ctx).writeText(arr.toString())
    }

    fun add(ctx: Context, name: String, type: String, text: String): Doc? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val id = "doc_" + System.currentTimeMillis()
        textFile(ctx, id).writeText(clean)
        val doc = Doc(id, name, type, clean.length, System.currentTimeMillis())
        val docs = list(ctx) + doc
        saveIndex(ctx, docs)
        return doc
    }

    fun delete(ctx: Context, id: String) {
        textFile(ctx, id).delete()
        saveIndex(ctx, list(ctx).filterNot { it.id == id })
    }

    fun totalChars(ctx: Context): Int = list(ctx).sumOf { it.chars }

    /** 检索与问题最相关的片段（跨资料），返回可直接拼进 prompt 的文本 */
    fun retrieve(ctx: Context, question: String, maxChars: Int = MAX_CHARS): String {
        val docs = list(ctx)
        if (docs.isEmpty() || question.isBlank()) return ""
        val q = question.filter { it.isLetterOrDigit() }
        if (q.length < 2) return ""
        data class Hit(val score: Double, val name: String, val text: String)
        val hits = ArrayList<Hit>()
        for (d in docs) {
            val body = try { textFile(ctx, d.id).readText() } catch (e: Exception) { continue }
            var start = 0
            while (start < body.length) {
                val end = minOf(start + CHUNK, body.length)
                val chunk = body.substring(start, end)
                val score = score(q, chunk)
                if (score > 0.15) hits.add(Hit(score, d.name, chunk))
                if (end >= body.length) break
                start += CHUNK - OVERLAP
            }
        }
        if (hits.isEmpty()) return ""
        val top = hits.sortedByDescending { it.score }.take(3)
        val sb = StringBuilder("\n\n【候选人资料库检索结果（真实项目数据，优先使用其中的数字）】\n")
        var used = 0
        for (h in top) {
            val piece = "〔${h.name}｜相关度${"%.2f".format(h.score)}〕${h.text.trim()}\n"
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
