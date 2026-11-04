package com.xingyihui.interviewassistant.diag

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 博弈话术生成（对齐需求文档 F3 / 话术铁则 0-4）。
 *
 * 一次调用返回两段：
 *  - SAY：可直接原样说出口的开场话术（第一人称、带数字、≤60 字）
 *  - NEXT：预判追问 + 一句话补强口径（≤50 字）
 * 刻意不用流式：整段 ≤60 字，一次返回比分片拼接更稳，且满足「秒出」。
 */
object StrategyEngine {

    data class Advice(
        val opening: String = "",
        val followup: String = "",
        val elapsedMs: Long = 0,
        val error: String = ""
    )

    const val URL = "https://api.deepseek.com/v1/chat/completions"
    private const val MODEL = "deepseek-chat"

    /** 话术铁则（浓缩自需求文档 §四），改这里不要散落到别处 */
    private const val RULES = """
你是求职者的「面试博弈陪练」，帮他当场组织可直接说出口的回答。

必须遵守：
1. SAY 行必须是第一人称、能原样说出口的完整句子，必须带具体数字（金额/比例/时间），不超过 60 字。
2. 禁止元对话和指令式表达：不得出现「你可以说」「建议」「思路」「方向」「先…再…」「重点」「核心」「记住」「面试官」等词。
3. 数字要自洽：ROI = 收益 ÷ 投放；核销 = GTV × 核销率。收益与投放必须同时成立。
4. 资料里没有的数字，用已知数据推演并给出明确口径（例如「按行业 85%–90% 的核销率，50 万 GTV 核销 43–45 万」），
   绝对禁止说「不知道 / 需要确认 / 会后补充 / 我跟门店对一下 / 我主要负责 XX」。
5. NEXT 行写出面试官最可能追问的一个角度，并给一句补强口径，不超过 50 字，同样不要元对话。

只输出两行，严格用以下格式，不要任何多余文字：
SAY: <开场话术>
NEXT: <预判追问与补强>
"""

    fun buildSystemPrompt(tips: String): String =
        if (tips.isBlank()) RULES.trim() else RULES.trim() + "\n\n" + tips.trim()

    fun systemPromptForTest(tips: String = ""): String = buildSystemPrompt(tips)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 阻塞调用，必须在工作线程执行 */
    fun generate(question: String, tips: String, apiKey: String): Advice {
        val t0 = System.currentTimeMillis()
        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.4)
            put("max_tokens", 220)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", buildSystemPrompt(tips)))
                put(JSONObject().put("role", "user").put("content", "面试官提问：$question"))
            })
        }.toString()

        val req = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Advice(elapsedMs = System.currentTimeMillis() - t0,
                        error = "HTTP ${resp.code}: ${raw.take(120)}")
                }
                val content = JSONObject(raw).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                parse(content, System.currentTimeMillis() - t0)
            }
        } catch (e: Exception) {
            Advice(elapsedMs = System.currentTimeMillis() - t0, error = e.message ?: "unknown")
        }
    }

    private fun parse(content: String, elapsed: Long): Advice {
        var say = ""
        var next = ""
        for (line in content.lines()) {
            val t = line.trim()
            when {
                t.startsWith("SAY:") -> say = t.removePrefix("SAY:").trim()
                t.startsWith("NEXT:") -> next = t.removePrefix("NEXT:").trim()
            }
        }
        if (say.isEmpty()) {
            // 模型没按格式时退化：取第一行非空文本
            say = content.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("NEXT:") }.orEmpty()
        }
        return Advice(opening = clean(say), followup = clean(next), elapsedMs = elapsed)
    }

    /** 去引号/前缀，截到第一句（播报只读第一句，保证 ≤5 秒） */
    fun clean(s: String): String {
        var t = s.trim().trim('「', '」', '"', '“', '”', ' ')
        t = t.removePrefix("SAY:").removePrefix("NEXT:").trim()
        for (p in listOf('。', '？', '！', '；')) {
            val i = t.indexOf(p)
            if (i in 1 until t.length - 1) {
                t = t.substring(0, i + 1)
                break
            }
        }
        return t.trim()
    }

    /** 播报文本：第一句 + 24 字上限（中文 TTS ≈4.6 秒；文档要求播报严守 5 秒） */
    fun speechText(opening: String): String {
        val one = clean(opening)
        return if (one.length <= 24) one else one.take(24)
    }
}
