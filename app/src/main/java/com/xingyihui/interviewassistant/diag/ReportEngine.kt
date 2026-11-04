package com.xingyihui.interviewassistant.diag

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * F5 六维复盘报告：基于「候选人真实回答」打分，禁止用话术冒充回答。
 * 输出严格 JSON，落库到 filesDir/reports/。
 */
object ReportEngine {

    data class Dim(val name: String, val score: Int, val comment: String)
    data class Report(
        val dims: List<Dim>,
        val strengths: List<String>,
        val improvements: List<String>,
        val verdict: String,
        val raw: String = "",
        val error: String = ""
    ) {
        fun toJson(): String = JSONObject().apply {
            put("dims", JSONArray().apply {
                dims.forEach { d ->
                    put(JSONObject().put("name", d.name).put("score", d.score).put("comment", d.comment))
                }
            })
            put("strengths", JSONArray(strengths))
            put("improvements", JSONArray(improvements))
            put("verdict", verdict)
        }.toString()

        companion object {
            fun fromJson(s: String): Report? = try {
                val o = JSONObject(s)
                val arr = o.optJSONArray("dims") ?: JSONArray()
                val dims = (0 until arr.length()).map { i ->
                    val d = arr.getJSONObject(i)
                    Dim(d.optString("name"), d.optInt("score"), d.optString("comment"))
                }
                Report(
                    dims,
                    o.optJSONArray("strengths")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    o.optJSONArray("improvements")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    o.optString("verdict")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private val DIMS = listOf("内容完整度", "数据可信度", "逻辑自洽", "表达结构", "追问防御", "时间控制")

    private const val SYSTEM = """
你是面试教练，负责根据「候选人这一轮的真实回答」写复盘报告。

铁律：
1. 只评估候选人真实说出口的内容，绝不能把 AI 给的建议当成候选人回答。
2. 如果回答几乎没有有效内容（只有语气词/沉默），六维分数一律给 20 分以下，并在 comment 里点明"本轮几乎没有有效回答"。
3. comment 必须引用回答里的原话片段或具体缺失点，不要空泛。
4. 只输出 JSON，不要 markdown 代码块，不要解释。

JSON 格式（六维固定为：内容完整度/数据可信度/逻辑自洽/表达结构/追问防御/时间控制）：
{"dims":[{"name":"内容完整度","score":75,"comment":"..."},...6项],
 "strengths":["..."],"improvements":["..."],"verdict":"一句话总评"}
分数 0-100 的整数。
"""

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    fun generate(question: String, answer: String, apiKey: String, history: String = ""): Report {
        if (answer.isBlank()) {
            return Report(
                DIMS.map { Dim(it, 10, "本轮几乎没有有效回答（仅语气词或静音），无法评估。") },
                emptyList(),
                listOf("先用一句话把问题答完：结论 + 一个数字 + 一个动作。", "回答前停 1 秒组织结构，避免连续语气词。"),
                "本轮没有形成有效回答，建议按话术提示先完整说一遍。"
            )
        }
        val body = JSONObject().apply {
            put("model", "deepseek-chat")
            put("temperature", 0.3)
            put("max_tokens", 900)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM.trim()))
                put(JSONObject().put("role", "user").put("content",
                    (if (history.isBlank()) "" else history.trim() + "\n\n") +
                        "本轮面试问题：$question\n本轮候选人的真实回答（转写原文）：$answer"))
            })
        }.toString()
        val req = Request.Builder()
            .url(StrategyEngine.URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Report(emptyList(), emptyList(), emptyList(), "", error = "HTTP ${resp.code}")
                val content = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                val json = content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1)
                val parsed = Report.fromJson(json)
                    ?: return Report(emptyList(), emptyList(), emptyList(), "", raw = content, error = "解析失败")
                parsed.copy(raw = content)
            }
        } catch (e: Exception) {
            Report(emptyList(), emptyList(), emptyList(), "", error = e.message ?: "unknown")
        }
    }
}
