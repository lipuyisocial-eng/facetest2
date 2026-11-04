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
        val question: String = "",
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
6. 【防止编造履历】只有【候选人资料库检索结果】里的数字才能当作"你的真实经历"。
   内置面试知识库里的示例、数字、经历都是他人的样例，禁止改写为第一人称（不得出现「我做了3年…」「我管过12家门店…」这类未确认履历）。
   没有用户上传资料时，SAY 用行业通用口径表述（如「按行业 85%–90% 的核销率，50 万 GTV 核销 43–45 万」），
   可以用「我们这类门店」「按行业口径」这类可复用主语，仍然要能直接说出口。

输入可能是「一段混杂的对话」：既有面试官提问，也可能包含候选人自己的话、口头语、他人插话。
你必须先判断「面试官真正问的问题」，然后用可复用口话说出口回答；遇到「注册」这类词，结合上下文和候选人资料理解其真实含义
（例如小程序注册、会员注册、渠道引流注册），不要望文生义。

严格输出三行，不要任何多余文字：
Q: <你判断出的面试官问题，20 字以内>
SAY: <开场话术>
NEXT: <预判追问与补强>
"""

    private const val NO_DATA_RULE = """
【本次没有任何用户上传的项目资料】
SAY 必须使用可复用的行业口径主语（如「我们这类门店」「按行业口径」），
严禁出现「我做过 N 年」「我管过 N 家」「我带过/负责过…」这类未经用户确认的个人履历表述；
内置知识库里的样例只是他人的答案，不能改写成你自己的经历。
"""

    fun buildSystemPrompt(tips: String, hasUserData: Boolean = true): String {
        val base = if (tips.isBlank()) RULES.trim() else RULES.trim() + "\n\n" + tips.trim()
        return if (hasUserData) base else base + "\n" + NO_DATA_RULE.trim()
    }

    fun systemPromptForTest(tips: String = ""): String = buildSystemPrompt(tips)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 阻塞调用，必须在工作线程执行；history 为本场已发生的对话（可空） */
    fun generate(
        question: String,
        tips: String,
        apiKey: String,
        history: String = "",
        hasUserData: Boolean = false
    ): Advice {
        val t0 = System.currentTimeMillis()
        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.4)
            put("max_tokens", 220)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", buildSystemPrompt(tips, hasUserData)))
                put(JSONObject().put("role", "user").put("content",
                    (if (history.isBlank()) "" else history.trim() + "\n\n") + "面试官提问：$question"))
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
                val parsed = parse(content, System.currentTimeMillis() - t0)
                if (!hasUserData && looksFabricated(parsed.opening)) {
                    android.util.Log.i("InterviewApp", "话术含未确认个人履历，重试一次: ${parsed.opening.take(40)}")
                    return retryWithoutFabrication(question, tips, apiKey, history, t0)
                }
                parsed
            }
        } catch (e: Exception) {
            Advice(elapsedMs = System.currentTimeMillis() - t0, error = e.message ?: "unknown")
        }
    }

    /** 无上传资料时，是否出现「我做过/我管过/N年」这类个人履历 */
    private fun looksFabricated(text: String): Boolean {
        val pat = Regex("(我|本人)[^。；]{0,20}(\\d+\\s*(年|家|个|款|次)|做过|做了|管过|带过|负责过|干了)")
        return pat.containsMatchIn(text)
    }

    private fun retryWithoutFabrication(
        question: String, tips: String, apiKey: String, history: String, t0: Long
    ): Advice {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.3)
            put("max_tokens", 220)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", buildSystemPrompt(tips, false)))
                put(JSONObject().put("role", "user").put("content",
                    (if (history.isBlank()) "" else history.trim() + "\n\n") +
                        "面试官提问：$question\n\n上一次回答违反了「禁止编造个人履历」：出现了第一人称年限/门店数。请重写，SAY 用行业口径，不得出现「我做过/我管过/N年」这类表述。"))
            })
        }.toString()
        val req = Request.Builder().url(URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Advice(elapsedMs = System.currentTimeMillis() - t0, error = "HTTP ${resp.code}")
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
        var q = ""
        for (line in content.lines()) {
            val t = line.trim()
            when {
                t.startsWith("Q:") -> q = t.removePrefix("Q:").trim()
                t.startsWith("SAY:") -> say = t.removePrefix("SAY:").trim()
                t.startsWith("NEXT:") -> next = t.removePrefix("NEXT:").trim()
            }
        }
        if (say.isEmpty()) {
            // 模型没按格式时退化：取第一行非空文本
            say = content.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("NEXT:") }.orEmpty()
        }
        return Advice(question = q.trim('「', '」'), opening = clean(say), followup = clean(next), elapsedMs = elapsed)
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

    /**
     * 播报文本：完整第一句（不再硬截 24 字导致话说一半），仅 80 字安全上限。
     * 实战模式需要「照着念」，完整性优先于 5 秒限制。
     */
    fun speechText(opening: String): String {
        val one = clean(opening)
        return if (one.length <= 80) one else one.take(80)
    }
}
