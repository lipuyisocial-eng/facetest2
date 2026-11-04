package com.xingyihui.interviewassistant.diag

import android.content.Context
import org.json.JSONObject
import java.io.File

/** F5 复盘报告落库：filesDir/reports/rep_<ts>.json（问题 + 真实回答 + 六维报告） */
object ReportStore {

    data class Item(
        val id: String,
        val question: String,
        val answer: String,
        val at: Long,
        val totalScore: Int
    )

    private fun dir(ctx: Context): File = File(ctx.filesDir, "reports").apply { if (!exists()) mkdirs() }

    fun save(ctx: Context, question: String, answer: String, report: ReportEngine.Report): Item? {
        val at = System.currentTimeMillis()
        val id = "rep_$at"
        val obj = JSONObject().apply {
            put("question", question)
            put("answer", answer)
            put("at", at)
            put("report", JSONObject(report.toJson()))
        }
        return try {
            File(dir(ctx), "$id.json").writeText(obj.toString())
            Item(id, question, answer, at, total(report))
        } catch (e: Exception) {
            null
        }
    }

    private fun total(r: ReportEngine.Report): Int =
        if (r.dims.isEmpty()) 0 else r.dims.sumOf { it.score }.coerceIn(0, 100)

    fun list(ctx: Context): List<Item> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                val rep = ReportEngine.Report.fromJson(o.getJSONObject("report").toString())
                Item(f.nameWithoutExtension, o.optString("question"), o.optString("answer"),
                    o.optLong("at"), if (rep == null) 0 else total(rep))
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.at }
    }

    fun load(ctx: Context, id: String): Triple<String, String, ReportEngine.Report?>? {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            Triple(o.optString("question"), o.optString("answer"),
                ReportEngine.Report.fromJson(o.getJSONObject("report").toString()))
        } catch (e: Exception) {
            null
        }
    }
}
