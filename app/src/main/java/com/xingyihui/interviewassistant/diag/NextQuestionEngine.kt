package com.xingyihui.interviewassistant.diag

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 练习模式「下一题」引擎：AI 当面试官，根据候选人这一轮的真实回答 + 上传资料 + 前文，
 * 决定是顺着回答深挖，还是换一道新题。
 */
object NextQuestionEngine {

    data class Question(val text: String, val kind: String, val error: String = "")

    private val SYSTEM = """
你是资深面试官，正在对候选人做连续面试。
根据「上一个问题」「候选人的真实回答」「参考资料（候选人项目资料 + 面试知识库）」决定下一个问题：
- 如果回答里有具体数字、项目细节没讲透，就顺着深挖（kind=深挖），例如追问口径、拆解、归因；
- 如果这个点已经聊完，就换一个与岗位相关的常见问题（kind=切换）；
- 问题必须口语化、能直接问出口，不超过 40 字，一次只问一个问题，不要点评、不要给建议、不要解释。
- 只能引用候选人真实回答里出现过的内容；如果本轮回答几乎为空，就换一道更基础或更具体的问题，不要引用没出现过的数字。
- 如果候选人回答明显太短（例如自我介绍只说了姓名和年龄），先礼貌地请对方补充/展开（如「好的，能再多说几句工作经历吗」），不要直接跳到下一个话题。

只输出 JSON：{"q":"下一个问题","kind":"深挖或切换"}
""".trim()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    fun generate(
        currentQuestion: String,
        answer: String,
        history: String,
        tips: String,
        apiKey: String,
        askedCount: Int
    ): Question {
        val userContent = buildString {
            append("上一个问题：").append(currentQuestion).append("\n")
            append("候选人的真实回答：").append(if (answer.isBlank()) "（本轮几乎没说话）" else answer.take(500)).append("\n")
            if (history.isNotBlank()) append(history.trim()).append("\n")
            if (tips.isNotBlank()) append(tips.trim()).append("\n")
            append("这已经是第 ").append(askedCount).append(" 个问题。")
        }
        val body = JSONObject().apply {
            put("model", "deepseek-chat")
            put("temperature", 0.6)
            put("max_tokens", 160)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM))
                put(JSONObject().put("role", "user").put("content", userContent))
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
                if (!resp.isSuccessful) return Question("", "", "HTTP ${resp.code}")
                val content = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                val json = content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1)
                val o = JSONObject(json)
                Question(o.optString("q").trim(), o.optString("kind", "切换"))
            }
        } catch (e: Exception) {
            Question("", "", e.message ?: "unknown")
        }
    }
}
