package com.xingyihui.interviewassistant.diag

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.sqrt

/**
 * 面试博弈助手 · 正式版 v1.4.1
 *
 * 核心链路：点击开始 → TTS 播题 + 录音（16kHz 单声道 16bit）→ 讯飞 rtasr_llm 实时转写
 * （OkHttp WebSocket，独立推流线程 40ms 固定节奏）→ 按句子重组后实时上屏 → 停止后发 end 帧
 * → DeepSeek 流式生成面试反馈（命中知识库语料则注入参考要点）。
 *
 * 保活：前台服务 + 通知（ColorOS 锁屏/后台会冻结应用，实测推流线程无 CPU 时间片 → 讯飞静默拒识）；
 * 屏幕常亮 + setShowWhenLocked（转写期间保持可见前台）。
 *
 * 密钥说明：讯飞 APP_ID/API_KEY 按用户决策内置 APK；正式对外发布版必须移除并改走服务端代理。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "InterviewApp"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val REQ_RECORD_AUDIO = 100
        private const val MAX_DURATION_MS = 60_000L // 单次答题上限：60s 自动结束（防异常悬挂）

        // 讯飞实时转写大模型（rtasr_llm，用户 2026-11-04 开通）—— APP_ID/API_KEY 内置 APK（控制台 rtasr_llm 产品页 WebAPI 认证）；
        // API_SECRET 走 local.properties → BuildConfig（不入库，防密钥泄露；正式发布建议改服务端代理）
        private const val XF_APP_ID = "6eb32081"
        private const val XF_API_KEY = "876f9549559a4592a7fc8195fbbe343b"
        private const val XF_API_SECRET = BuildConfig.XF_API_SECRET

        // DeepSeek 回答反馈密钥 —— 用户提供（2026-11-04）；经 local.properties 注入 BuildConfig（不入库，防密钥泄露）；
        // 正式对外发布必须改服务端代理，禁止前端直连暴露
        private const val DEEPSEEK_API_KEY = BuildConfig.DEEPSEEK_API_KEY
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val DEEPSEEK_MODEL = "deepseek-chat"
        private const val FEEDBACK_SYSTEM_PROMPT =
            "你是资深面试官。根据候选人对面试问题的回答，用中文给出简短点评：先说1句优点，" +
            "再说1-2句可改进的具体建议。直接输出内容，不要客套，不要使用markdown符号，不超过120字。"

        // 知识库语料（assets/corpus/*.txt，由 CC014 面试语料按主题切分生成，合计约 136KB）
        private val CORPUS_TOPICS = arrayOf(
            "zwojieshao", "lizhiyuanyin", "youquedian", "xinzidaiyu", "zhiyeguihua",
            "jiaban", "yingsheng", "tiaocao", "yali", "tuandui", "xiangmu",
            "fanwen", "kongchuan", "qiweifan", "tongxue"
        )
        // 主题 → 命中关键词（题目/回答中出现即注入对应语料要点）
        private val CORPUS_KEYWORDS = mapOf(
            "zwojieshao" to arrayOf("自我介绍", "介绍一下自己", "做个自我介绍"),
            "lizhiyuanyin" to arrayOf("离职原因", "为什么离职", "离开上家", "上家公司"),
            "youquedian" to arrayOf("优点", "缺点", "优势", "劣势", "不足", "长处"),
            "xinzidaiyu" to arrayOf("薪资", "工资", "薪酬", "待遇", "期望薪资", "薪水"),
            "zhiyeguihua" to arrayOf("职业规划", "职业发展", "未来规划", "长期规划", "发展目标"),
            "jiaban" to arrayOf("加班", "996", "工作强度"),
            "yingsheng" to arrayOf("应届", "校招", "毕业生", "刚毕业", "实习"),
            "tiaocao" to arrayOf("跳槽", "频繁换工作", "换了几份"),
            "yali" to arrayOf("压力", "抗压", "焦虑"),
            "tuandui" to arrayOf("团队", "协作", "合作", "同事", "沟通", "冲突"),
            "xiangmu" to arrayOf("项目经验", "做过什么项目", "项目经历", "负责过", "做过哪些"),
            "fanwen" to arrayOf("你有什么问题", "反问", "还有什么想问"),
            "kongchuan" to arrayOf("空窗期", "断档", "没有工作", "待业"),
            "qiweifan" to arrayOf("为什么选择我们", "为什么来我们", "了解我们公司", "期望"),
            "tongxue" to arrayOf("住在哪里", "通勤", "爱好", "兴趣", "性格", "老家")
        )

        private val QUESTIONS = arrayOf(
            "请先做个简短的自我介绍。",
            "你的主要优点和缺点是什么？",
            "为什么选择我们公司？",
            "你的职业规划是怎样的？",
            "为什么从上一家公司离职？",
            "你期望的薪资是多少？",
            "如何看待加班这件事？",
            "如果和同事意见不合，你会怎么处理？"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var statusView: TextView
    private lateinit var questionView: TextView
    private lateinit var resultView: TextView
    private lateinit var feedbackView: TextView
    private lateinit var btnStart: TextView

    // TTS 播题
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var questionIdx = 0
    private var lastQuestion = ""

    // 转写结果：rtasr_llm 每帧给的是「当前句子的累计文本」（bg=句子起点，type=0 定稿），
    // 因此按句子重组：已定稿句 committedText + 正在说的当前句 curSentence
    private val committedText = StringBuilder()
    private var curSentence = ""
    private var curBg = -1L
    private var curEd = 0L
    private var ttsStartMs = -1L
    private var ttsEndMs = -1L
    private var droppedSentences = 0

    private fun resultText(): String = (committedText.toString() + curSentence).trim()

    // 录音
    private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false
    private var recordThread: Thread? = null
    private var recordBytes = 0
    private var recordStartElapsed = 0L
    private var lastRms = 0.0
    private var recordFile: File? = null
    private var recordStream: FileOutputStream? = null

    // 讯飞转写
    private var okWs: WebSocket? = null
    @Volatile private var transcribing = false
    private var sendFrames = 0
    private var rawFramesLogged = 0
    private var sendBytes = 0L
    private val frameQueue = LinkedBlockingQueue<ByteArray>(512)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 转写期间保持应用前台：避免 ColorOS 后台网络限制（实测：后台态推流零 result）
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        buildUi()
        log("应用启动 v1.4.1（正式版·实时转写·知识库）")
        log("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        loadCorpus()
        checkRecordPermission()
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            setBackgroundColor(0xFFF5F6FA.toInt())
        }

        val title = TextView(this).apply {
            text = "面试博弈助手"
            textSize = 24f
            setTextColor(0xFF1A1D26.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "实时语音转写 · 你说我记"
            textSize = 13f
            setTextColor(0xFF8A8F9C.toInt())
            setPadding(0, dp(4), 0, dp(16))
        }

        statusView = TextView(this).apply {
            text = "就绪"
            textSize = 15f
            setTextColor(0xFF3D4356.toInt())
            setPadding(0, 0, 0, dp(10))
        }

        val questionLabel = TextView(this).apply {
            text = "当前题目"
            textSize = 13f
            setTextColor(0xFF8A8F9C.toInt())
            setPadding(0, 0, 0, dp(6))
        }

        questionView = TextView(this).apply {
            text = "点击开始，面试官将通过语音提问"
            textSize = 16f
            setTextColor(0xFF1A1D26.toInt())
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, dp(14))
        }

        val resultLabel = TextView(this).apply {
            text = "识别结果"
            textSize = 13f
            setTextColor(0xFF8A8F9C.toInt())
            setPadding(0, 0, 0, dp(6))
        }

        resultView = TextView(this).apply {
            text = "面试官语音提问后，你的回答将实时转为文字。"
            textSize = 15f
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.35f)
            setTextColor(0xFFE8F0F8.toInt())
            setBackgroundColor(0xFF14181F.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val scroll = ScrollView(this).apply {
            addView(resultView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))
        }

        val feedbackLabel = TextView(this).apply {
            text = "面试反馈"
            textSize = 13f
            setTextColor(0xFF8A8F9C.toInt())
            setPadding(0, dp(6), 0, dp(6))
        }

        feedbackView = TextView(this).apply {
            text = "答题结束后，将基于你的回答生成点评与改进建议。"
            textSize = 14f
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.35f)
            setTextColor(0xFFE8F0F8.toInt())
            setBackgroundColor(0xFF1A2230.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val feedbackScroll = ScrollView(this).apply {
            addView(feedbackView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))
        }

        btnStart = TextView(this).apply {
            text = "开始答题"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF2E7CF6.toInt())
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener { onStartStopClicked() }
        }
        val btnLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(20)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusView)
        root.addView(questionLabel)
        root.addView(questionView)
        root.addView(resultLabel)
        root.addView(scroll)
        root.addView(feedbackLabel)
        root.addView(feedbackScroll)
        root.addView(btnStart, btnLp)
        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun onStartStopClicked() {
        if (transcribing) stopTranscribe("用户点击停止")
        else startTranscribe()
    }

    private fun setButtonRunning(running: Boolean) {
        handler.post {
            btnStart.text = if (running) "停止答题" else "开始答题"
            btnStart.setBackgroundColor(if (running) 0xFFE5484D.toInt() else 0xFF2E7CF6.toInt())
        }
    }

    private val resultPlaceholder = "面试官语音提问后，你的回答将实时转为文字。"

    /** 转写区渲染：整段重绘（已定稿句 + 当前句），避免逐帧追加导致的重复爆字 */
    private fun renderResult() {
        val text = resultText()
        handler.post {
            resultView.text = if (text.isEmpty()) resultPlaceholder else text
            (resultView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** 句子定稿：过滤面试官提问句后并入已定稿文本 */
    private fun commitSentence(text: String, bg: Long, ed: Long) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (isInterviewerQuestion(t, bg, ed)) {
            droppedSentences++
            log("过滤面试官提问句(bg=${bg}ms ed=${ed}ms): $t")
            return
        }
        committedText.append(t)
    }

    /**
     * 面试官提问过滤（不依赖 rl 编号）：
     * 盲分说话人编号在同一会话内不稳定（实测同一轮里 TTS 提问句被标成 rl=2、环境说话人 rl=1/3；
     * 另一轮全是 rl=0），不能按编号定角色。改用「播报时间窗 ∩ 文本相似度」双条件，确定性可验证。
     */
    private fun isInterviewerQuestion(text: String, bg: Long, ed: Long): Boolean {
        val q = lastQuestion
        if (q.isEmpty() || ttsStartMs < 0) return false
        val inTtsWindow = ed >= ttsStartMs - 800 && bg <= ttsEndMs + 800
        if (!inTtsWindow) return false
        val sim = charOverlap(q, text)
        return sim >= 0.55
    }

    /** 字符多重集重合率：|交集| / |问题文本|，忽略标点与空白 */
    private fun charOverlap(question: String, said: String): Double {
        fun norm(s: String) = s.filter { it.isLetterOrDigit() }
        val q = norm(question)
        val s = norm(said)
        if (q.isEmpty() || s.isEmpty()) return 0.0
        val pool = s.toMutableList()
        var hit = 0
        for (c in q) {
            val i = pool.indexOf(c)
            if (i >= 0) { hit++; pool.removeAt(i) }
        }
        return hit.toDouble() / q.length
    }

    private fun appendFeedback(text: String) {
        handler.post {
            val placeholder = "答题结束后，将基于你的回答生成点评与改进建议。"
            feedbackView.text = if (feedbackView.text.isEmpty() || feedbackView.text == placeholder)
                text else feedbackView.text.toString() + text
            (feedbackView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun log(msg: String) = Log.i(TAG, msg)

    // ---------- 知识库语料（CC014 面试语料，assets/corpus/*.txt） ----------
    private val corpusCache = HashMap<String, String>()

    private fun loadCorpus() {
        try {
            for (topic in CORPUS_TOPICS) {
                val path = "corpus/$topic.txt"
                val text = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (text.isNotBlank()) corpusCache[topic] = text
            }
            log("知识库语料加载完成: ${corpusCache.size}/${CORPUS_TOPICS.size} 个主题")
        } catch (e: Exception) {
            log("知识库语料加载失败: ${e.message}")
        }
    }

    /** 题目/回答关键词命中知识库主题 → 返回注入用的要点片段（最多 2 主题，每段 ≤900 字） */
    private fun matchCorpus(question: String, answer: String): String {
        val haystack = "$question $answer"
        val hits = LinkedHashSet<String>()
        for ((topic, kws) in CORPUS_KEYWORDS) {
            if (hits.size >= 2) break
            if (kws.any { haystack.contains(it) }) hits.add(topic)
        }
        if (hits.isEmpty()) return ""
        val sb = StringBuilder("\n\n【参考要点（来自面试知识库，仅作点评依据，不要照抄）】\n")
        for (t in hits) {
            val c = corpusCache[t]
            if (!c.isNullOrBlank()) sb.append("[$t] ").append(c.take(900)).append("\n")
        }
        return sb.toString()
    }

    private fun status(msg: String) { handler.post { statusView.text = msg } }

    // ---------- 权限 ----------
    private fun checkRecordPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            log("RECORD_AUDIO 已授权")
        } else {
            log("请求 RECORD_AUDIO 运行时权限")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("RECORD_AUDIO 授权成功")
                status("麦克风已授权，可以开始答题")
            } else {
                log("RECORD_AUDIO 被拒绝")
                status("麦克风权限被拒绝，无法录音转写")
            }
        }
    }

    // ---------- TTS 播题 ----------
    private fun initTtsIfNeeded() {
        if (ttsReady) return
        log("初始化 TextToSpeech…")
        tts = TextToSpeech(this) { status ->
            handler.post {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    val lang = tts?.setLanguage(Locale.CHINA)
                    log("TTS 初始化成功；setLanguage(zh_CN)=$lang")
                } else {
                    log("TTS 初始化失败 status=$status（检查系统 TTS 引擎与语音包）")
                }
            }
        }
    }

    /** 播报当前题目（面试官提问）；未就绪则静默降级为仅显示文字 */
    private fun speakQuestion() {
        val idx = questionIdx
        val q = QUESTIONS[idx % QUESTIONS.size]
        lastQuestion = q
        handler.post { questionView.text = "第${idx % QUESTIONS.size + 1}题：$q" }
        if (!ttsReady) {
            log("TTS 未就绪，题目仅显示不播报")
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                ttsStartMs = SystemClock.elapsedRealtime() - recordStartElapsed
                log("TTS 播题开始 @${ttsStartMs}ms（计入面试官提问过滤时间窗）")
            }
            override fun onDone(utteranceId: String?) {
                ttsEndMs = SystemClock.elapsedRealtime() - recordStartElapsed
                log("TTS 播题完成 @${ttsEndMs}ms")
            }
            override fun onError(utteranceId: String?) { log("TTS 播题错误") }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) { log("TTS 播题错误 code=$errorCode") }
        })
        val res = tts?.speak(q, TextToSpeech.QUEUE_FLUSH, null, "question_$idx")
        log("TTS speak 题目返回=$res")
        questionIdx++
    }

    // ---------- 录音 ----------
    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun createRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .setEncoding(ENCODING)
                .build())
            .setBufferSizeInBytes(minBuf * 4)
            .build()
        return if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            log("AudioRecord 初始化失败 state=${rec.state}")
            rec.release()
            null
        }
    }

    /** 启动录音；每帧（40ms/1280B）回调 onFrame，用于入队推流 */
    private fun startRecording(onFrame: ((ByteArray, Int) -> Unit)? = null): Boolean {
        if (!hasMicPermission()) {
            log("无麦克风权限，终止")
            status("无麦克风权限")
            return false
        }
        if (recording) {
            log("已在录音中，忽略重复启动")
            return false
        }
        val rec = createRecorder() ?: return false
        audioRecord = rec
        recordBytes = 0
        lastRms = 0.0
        recordStartElapsed = SystemClock.elapsedRealtime()

        var stream: FileOutputStream? = null
        try {
            val f = File(filesDir, "rec_${System.currentTimeMillis()}.pcm")
            stream = FileOutputStream(f)
            recordFile = f
        } catch (e: Exception) {
            log("无法创建录音文件: ${e.message}")
        }
        recordStream = stream

        rec.startRecording()
        recording = true
        log("录音启动 @${SAMPLE_RATE}Hz")
        recordThread = Thread {
            val buf = ByteArray(1280) // 40ms @16k/16bit/mono
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    recordBytes += n
                    if (n % 2 == 0) {
                        var sum = 0.0
                        var idx = 0
                        while (idx < n) {
                            val s = (buf[idx].toInt() and 0xFF or (buf[idx + 1].toInt() shl 8)).toShort().toDouble()
                            sum += s * s
                            idx += 2
                        }
                        lastRms = sqrt(sum / (n / 2))
                    }
                    stream?.write(buf, 0, n)
                    onFrame?.invoke(buf, n)
                } else if (n < 0) {
                    log("录音 read 返回错误码 $n")
                }
            }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    private fun stopRecording(reason: String) {
        if (!recording && recordStream == null) return
        val wasRecording = recording
        recording = false
        recordThread?.join(1500)
        recordThread = null
        try { recordStream?.flush(); recordStream?.close() } catch (e: Exception) { log("关闭录音文件异常: ${e.message}") }
        recordStream = null
        val rec = audioRecord
        if (rec != null) {
            try { rec.stop() } catch (e: IllegalStateException) { log("AudioRecord stop 异常: ${e.message}") }
            rec.release()
        }
        audioRecord = null
        if (wasRecording) {
            val durMs = SystemClock.elapsedRealtime() - recordStartElapsed
            log("录音停止: $reason | 时长=${"%.1f".format(durMs / 1000.0)}s | 采集=${recordBytes}B")
        }
    }

    // ---------- 讯飞实时转写大模型（rtasr_llm）----------
    private var currentSessionId = ""

    /**
     * rtasr_llm 新协议（office-api-ast-dx）：
     * - 端点 wss://office-api-ast-dx.iflyaisol.com/ast/communicate/v1
     * - 签名：全部请求参数（不含 signature）按参数名升序排序，键值分别 URL 编码后以 & 拼接；
     *   HmacSHA1(baseString, accessKeySecret) → Base64 → URL 编码得到 signature
     * - role_type=2 实时角色分离（盲分）：result 的 cw[0].rl 为说话人切换标记（0 继续上一说话人）
     */
    private fun buildXfUrl(): String {
        currentSessionId = UUID.randomUUID().toString()
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        val params = sortedMapOf(
            "accessKeyId" to XF_API_KEY,
            "appId" to XF_APP_ID,
            "audio_encode" to "pcm_s16le",
            "lang" to "autodialect",
            "role_type" to "2",
            "samplerate" to "16000",
            "utc" to utc,
            "uuid" to currentSessionId
        )
        val baseString = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(XF_API_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val sig = android.util.Base64.encodeToString(
            mac.doFinal(baseString.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
        return "wss://office-api-ast-dx.iflyaisol.com/ast/communicate/v1?" +
            baseString + "&signature=" + URLEncoder.encode(sig, "UTF-8")
    }

    private fun startTranscribe() {
        if (transcribing) return
        transcribing = true
        sendFrames = 0
        rawFramesLogged = 0
        sendBytes = 0L
        committedText.setLength(0)
        curSentence = ""
        curBg = -1L
        curEd = 0L
        droppedSentences = 0
        ttsStartMs = -1L
        ttsEndMs = -1L
        handler.post { resultView.text = resultPlaceholder }
        frameQueue.clear()
        status("准备中…")
        setButtonRunning(true)
        // 前台服务保活：ColorOS 后台冻结会导致推流线程卡死
        try { startService(Intent(this, DiagService::class.java)) } catch (e: Exception) { log("前台服务启动失败: ${e.message}") }
        // 面试官语音提问（TTS 播题），与录音同时进行（诊断版 Test C 已验证同时录播不冲突）
        initTtsIfNeeded()
        handler.postDelayed({ speakQuestion() }, 600)

        val url = buildXfUrl()
        log("连接讯飞 RTASR-LLM…（role_type=2 角色分离）")

        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url).build()
        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                log("WS 打开，启动推流线程")
                okWs = ws
                // 独立推流线程 + 40ms 固定节奏（讯飞对突发流静默拒识；与已验证的 OkHttp 文件模式节奏一致）
                Thread {
                    while (transcribing) {
                        val f = frameQueue.poll()
                        if (f != null) {
                            okWs?.send(f.toByteString())
                            sendFrames++
                            sendBytes += f.size
                        }
                        Thread.sleep(40)
                    }
                }.apply { isDaemon = true; name = "rtasr-push" }.start()
            }
            override fun onMessage(ws: WebSocket, text: String) { handleXfMessage(text) }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                log("WS 失败: ${t.message}")
                status("连接失败: ${t.message}")
                transcribing = false
                setButtonRunning(false)
                stopService(Intent(this@MainActivity, DiagService::class.java))
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { log("WS 关闭: $code $reason") }
        })

        if (!startRecording { buf, n -> frameQueue.offer(buf.copyOfRange(0, n)) }) {
            okWs?.close(1000, "stop")
            okWs = null
            transcribing = false
            setButtonRunning(false)
            return
        }
        status("录音转写中…（最长 60s）")
        handler.postDelayed({ if (transcribing) stopTranscribe("60s 上限自动结束") }, MAX_DURATION_MS)
    }

    private fun stopTranscribe(reason: String) {
        if (!transcribing) return
        transcribing = false
        log("结束转写: $reason")
        log("推流统计: sendFrames=$sendFrames sendBytes=$sendBytes（采集 ${recordBytes}B）最近帧 RMS=${"%.1f".format(lastRms)}")
        stopRecording(reason)
        try { tts?.stop() } catch (_: Exception) {}
        okWs?.send("{\"end\":true,\"sessionId\":\"$currentSessionId\"}".encodeUtf8())
        Thread.sleep(300) // 等 end 帧送达
        okWs?.close(1000, "done")
        okWs = null
        setButtonRunning(false)
        stopService(Intent(this, DiagService::class.java))
        status("转写结束")
        if (curSentence.isNotEmpty()) { commitSentence(curSentence, curBg, curEd); curSentence = "" }
        renderResult()
        log("转写文本: ${committedText.length}字（过滤提问句 $droppedSentences 句）")
        // 基于转写结果生成面试反馈（独立线程；等 result 下行收敛后触发）
        handler.postDelayed({ generateFeedback() }, 800)
    }

    // ---------- DeepSeek 面试反馈 ----------
    /**
     * 根据本轮转写文本调用 DeepSeek 生成点评与建议，SSE 流式上屏。
     * 开发验证辅助：files/test_feedback.txt 存在且转写文本为空时，以其内容作为测试回答文本（测试后删除）。
     */
    private fun generateFeedback() {
        val text = resultText()
        var source = "实时转写"
        var answer = text
        if (answer.isEmpty()) {
            val tf = File(filesDir, "test_feedback.txt")
            if (tf.exists()) {
                answer = tf.readText().trim()
                source = "测试文本"
                log("转写文本为空，使用 test_feedback.txt 测试回答（${answer.length}字）")
            }
        }
        if (answer.isEmpty()) {
            log("本轮未识别到回答文本，跳过反馈生成")
            appendFeedback("\n（本轮未识别到回答文本，无法生成反馈）")
            return
        }
        log("生成反馈: 题目=$lastQuestion | 回答($source)=${answer.take(40)}…")
        // 知识库命中注入：题目+回答关键词 → 语料要点拼进 system prompt
        val tips = matchCorpus(lastQuestion, answer)
        if (tips.isNotEmpty()) log("知识库命中，注入参考要点 ${tips.length} 字")
        Thread {
            try {
                val payload = JSONObject().apply {
                    put("model", DEEPSEEK_MODEL)
                    put("temperature", 0.5)
                    put("stream", true)
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", FEEDBACK_SYSTEM_PROMPT + tips))
                        put(JSONObject().put("role", "user").put("content",
                            "面试问题：$lastQuestion\n候选人的回答：$answer"))
                    })
                }.toString()
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)   // 显式超时：防弱网挂起（规范 02 FR-07）
                    .readTimeout(60, TimeUnit.SECONDS)      // 流式首字等待 + 整体
                    .build()
                val req = Request.Builder()
                    .url(DEEPSEEK_URL)
                    .header("Authorization", "Bearer $DEEPSEEK_API_KEY")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        log("DeepSeek HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                        appendFeedback("\n（反馈服务异常 HTTP ${resp.code}）")
                        return@Thread
                    }
                    val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream(), Charsets.UTF_8))
                    var line: String?
                    var first = true
                    while (reader.readLine().also { line = it } != null) {
                        val l = line!!
                        if (!l.startsWith("data:")) continue
                        val data = l.substring(5).trim()
                        if (data == "[DONE]") break
                        try {
                            val delta = JSONObject(data).optJSONArray("choices")
                                ?.optJSONObject(0)?.optJSONObject("delta")?.optString("content", "")
                            if (!delta.isNullOrEmpty()) {
                                if (first) { first = false; log("DeepSeek 流式开始") }
                                appendFeedback(delta)
                            }
                        } catch (e: Exception) { log("SSE 解析跳过: ${e.message}") }
                    }
                    log("DeepSeek 流式结束")
                }
            } catch (e: Exception) {
                log("DeepSeek 调用失败: ${e.message}")
                appendFeedback("\n（反馈生成失败: ${e.message}）")
            }
        }.apply { isDaemon = true; name = "deepseek-feedback" }.start()
    }

    private fun handleXfMessage(json: String) {
        try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data")
            val actionData = data?.optString("action") ?: ""
            val actionTop = root.optString("action")
            val msgType = root.optString("msg_type", "")
            when {
                actionData == "started" || actionTop == "started" -> {
                    // rtasr_llm：started 在 data.action，且返回服务端 sessionId（结束帧必须用它）
                    val sid = data?.optString("sessionId", "")
                    if (!sid.isNullOrEmpty()) currentSessionId = sid
                    log("讯飞开始识别 sessionId=$currentSessionId")
                    status("识别中…")
                }
                actionData == "error" || actionTop == "error" -> {
                    val desc = data?.optString("desc").orEmpty().ifEmpty { root.optString("desc") }
                        .ifEmpty { root.optString("msg") }
                    val code = data?.optInt("code", -1) ?: root.optInt("code", -1)
                    log("讯飞错误 code=$code desc=$desc")
                    status("讯飞错误: $desc")
                }
                msgType == "result" || actionTop == "result" -> {
                    // 原始帧采样（取前 12 帧），用于确认 sn/ls/type 等字段语义
                    if (rawFramesLogged < 60) {
                        rawFramesLogged++
                        val stDbg = data?.optJSONObject("cn")?.optJSONObject("st")
                        val wordsDbg = StringBuilder()
                        val rtDbg = stDbg?.optJSONArray("rt")
                        if (rtDbg != null) for (i in 0 until rtDbg.length()) {
                            val wsDbg = rtDbg.getJSONObject(i).optJSONArray("ws") ?: continue
                            for (j in 0 until wsDbg.length()) {
                                val cwDbg = wsDbg.getJSONObject(j).optJSONArray("cw") ?: continue
                                if (cwDbg.length() > 0) wordsDbg.append(cwDbg.getJSONObject(0).optString("w", ""))
                            }
                        }
                        log("帧 seg=${data?.optString("seg_id")} type=${stDbg?.optString("type")} ls=${data?.optBoolean("ls")} bg=${stDbg?.optString("bg")} ed=${stDbg?.optString("ed")} | $wordsDbg")
                    }
                    // rtasr_llm result 帧：msg_type=result，词在 data.cn.st.rt.ws.cw；经典协议为顶层 action=result
                    val cn = data?.optJSONObject("cn") ?: return
                    val st = cn.optJSONObject("st") ?: return
                    val rt = st.optJSONArray("rt") ?: return
                    val sb = StringBuilder()
                    var rlSeen = ""
                    // 逐词 rl 标记串（形如 "<2>我上一份工作<1>好的"），仅用于测量说话人编号↔实际说话人的映射
                    val rlWordLog = StringBuilder()
                    for (i in 0 until rt.length()) {
                        val seg = rt.getJSONObject(i)
                        val wsArr = seg.optJSONArray("ws") ?: continue
                        for (j in 0 until wsArr.length()) {
                            val w = wsArr.getJSONObject(j)
                            val cw = w.optJSONArray("cw") ?: continue
                            if (cw.length() > 0) {
                                val c = cw.getJSONObject(0)
                                val r = c.optString("rl", "")
                                val word = c.optString("w", "")
                                if (r.isNotEmpty() && r != "0") {
                                    rlSeen += "$r,"
                                    if (rlWordLog.length < 300) rlWordLog.append("<$r>")
                                }
                                if (rlWordLog.length < 300) rlWordLog.append(word)
                                sb.append(word)
                            }
                        }
                    }
                    // 按句子重组：bg 为句子起点，同一句内后续帧是累计文本（直接覆盖当前句），
                    // bg 跳变 = 新句开始（上一句先定稿）；type=0 = 该句定稿。
                    val bg = st.optString("bg", "0").toLongOrNull() ?: 0L
                    val ed = st.optString("ed", "0").toLongOrNull() ?: 0L
                    val isFinal = st.optString("type") == "0"
                    if (sb.isNotEmpty()) {
                        val ms = SystemClock.elapsedRealtime() - recordStartElapsed
                        log("讯飞识别(${ms}ms): $sb (rl切换: ${rlSeen.trimEnd(',')})")
                        log("rl逐词标记: $rlWordLog")
                        val text = sb.toString()
                        // 定稿帧(type=0)的 bg 会回退到句首甚至 0，不能用它判新句；
                        // 判新句要求 bg 跳变 >800ms 且新文本不是当前句的延续。
                        val bgJumped = curBg >= 0 && kotlin.math.abs(bg - curBg) > 800
                        val continued = curSentence.isNotEmpty() && text.startsWith(curSentence.take(4))
                        val newSentence = curBg < 0 || (bgJumped && !continued)
                        if (newSentence && curSentence.isNotEmpty()) {
                            commitSentence(curSentence, curBg, curEd)
                            curSentence = ""
                        }
                        if (newSentence) curBg = bg
                        curEd = ed
                        curSentence = text
                        if (isFinal) {
                            commitSentence(curSentence, curBg, curEd)
                            curSentence = ""
                            curBg = -1L
                        }
                        renderResult()
                    }
                }
            }
        } catch (e: Exception) {
            log("解析消息失败: ${e.message}")
        }
    }

    // ---------- 生命周期 ----------
    override fun onPause() {
        super.onPause()
        // 正式版转写依赖可见前台（ColorOS 后台会冻结网络），退后台即停止
        if (recording || transcribing) {
            log("onPause：自动停止转写")
            stopTranscribe("退后台自动结束")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        transcribing = false
        recording = false
        handler.removeCallbacksAndMessages(null)
        try { okWs?.close(1000, "destroy") } catch (_: Exception) {}
        okWs = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }
}
