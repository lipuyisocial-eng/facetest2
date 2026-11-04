package com.xingyihui.interviewassistant.diag

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.SeekBar
import android.app.Activity
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
        private const val REQ_DOC = 200
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
    private lateinit var strategyView: TextView
    private lateinit var nextView: TextView
    private lateinit var modeChip: TextView
    private lateinit var speakStateView: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var transcriptCard: LinearLayout
    private lateinit var nextCard: LinearLayout
    private lateinit var hintCard: LinearLayout
    private lateinit var feedbackCard: LinearLayout
    private lateinit var waveView: WaveView
    private var assistMode = false
    private var lastReportId: String? = null
    private var dlgRef: AlertDialog? = null

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
        window.statusBarColor = 0xFFF4F1EC.toInt()
        window.navigationBarColor = 0xFFF4F1EC.toInt()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        // 转写期间保持应用前台：避免 ColorOS 后台网络限制（实测：后台态推流零 result）
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        assistMode = Prefs.mode(this) == Prefs.MODE_ASSIST
        buildUi()
        log("应用启动 v1.8.0（在线视觉识别 · 六维雷达图）")
        log("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        loadCorpus()
        initTtsIfNeeded()   // 提前初始化：辅助模式/术语播报随时可用
        checkRecordPermission()
        // 调试钩子：adb am start -e debug_question "问题" 可直接驱动「真人辅助」提问识别链路（仅 debug 包）
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("debug_kb_file")?.let { name ->
                Thread {
                    try {
                        val f = File(filesDir, name)
                        val r = DocumentParser.parse(this, android.net.Uri.fromFile(f), name)
                        val doc = DocumentStore.add(this, name, r.method, r.text)
                        log("DEBUG KB 导入: $name method=${r.method} pages=${r.pages} chars=${r.text.length} saved=${doc != null} 前60字=${r.text.take(60).replace("\n", " ")}")
                    } catch (e: Exception) {
                        log("DEBUG KB 异常: ${e.javaClass.simpleName} ${e.message}")
                    }
                }.start()
            }
            intent?.getStringExtra("debug_ocr")?.let { path ->
                Thread {
                    try {
                        if (path == "draw") {
                            val bmp = android.graphics.Bitmap.createBitmap(900, 260, android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.BLACK
                                textSize = 48f
                            }
                            canvas.drawText("端艾堂2024年总营收2053万", 40f, 80f, paint)
                            canvas.drawText("美团投放6万 GTV50万", 40f, 160f, paint)
                            val txt = DocumentParser.visionOcr(bmp)
                            log("DEBUG OCR(draw) chars=${txt.length} 内容=${txt.replace("\n", " ").take(80)}")
                            return@Thread
                        }
                        val f = File(path)
                        val r = DocumentParser.parse(this, android.net.Uri.fromFile(f), f.name)
                        log("DEBUG OCR 解析: ${f.name} method=${r.method} pages=${r.pages} chars=${r.text.length} 前80字=${r.text.take(80).replace("\n", " ")}")
                    } catch (e: Exception) {
                        log("DEBUG OCR 异常: ${e.javaClass.simpleName} ${e.message}")
                    }
                }.start()
            }
            intent?.getStringExtra("debug_question")?.takeIf { it.isNotBlank() }?.let { raw ->
                val qs = raw.split("||").map { it.trim() }.filter { it.isNotEmpty() }
                assistMode = true
                refreshModeUi()
                qs.forEachIndexed { i, q ->
                    handler.postDelayed({ onDebugQuestion(q) }, 1200L + i * 12_000L)
                }
            }
        }
    }

    // ---------- UI（深色科技 HUD · 深墨底 + 青色荧光 + 大圆角） ----------
    private object C {
        // 方案 B · 浅色莫兰迪：暖白底 + 灰绿 / 豆沙 + 大圆角
        val BG = 0xFFF4F1EC.toInt()
        val CARD = 0xFFFFFDF9.toInt()
        val STROKE = 0x33D8D1C6
        val ACCENT = 0xFF8FA69B.toInt()   // 灰绿
        val ACCENT_SOFT = 0xFF5F7A6E.toInt()
        val ROSE = 0xFFC8A5A0.toInt()     // 豆沙
        val ROSE_DEEP = 0xFFB07F79.toInt()
        val TEXT = 0xFF2F2E2C.toInt()
        val BODY = 0xFF6E6A64.toInt()
        val DIM = 0xFF9A948C.toInt()
        val LABEL = 0xFFA9A29A.toInt()
        val RED = 0xFFB07F79.toInt()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, stroke: Int? = null, radius: Int = 24): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = rounded(C.CARD, C.STROKE, 22)
        elevation = dp(6).toFloat()
    }

    private fun label(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 10f
        letterSpacing = 0.18f
        setTextColor(C.LABEL)
        setPadding(dp(2), 0, 0, dp(8))
    }

    private fun cardLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            setPadding(dp(18), dp(44), dp(18), dp(16))
        }

        // 顶栏：品牌 + 模式胶囊 + 设置
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val brand = TextView(this).apply {
            text = "面试博弈助手"
            textSize = 19f
            setTextColor(C.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        modeChip = TextView(this).apply {
            text = if (assistMode) "真人辅助" else "练习模式"
            textSize = 12f
            setTextColor(C.ACCENT_SOFT)
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(7), dp(13), dp(7))
            background = rounded(0xFFE4EAE5.toInt(), null, 999)
            setOnClickListener { toggleMode() }
        }
        val reportBtn = TextView(this).apply {
            text = "复盘"
            textSize = 12f
            setTextColor(C.DIM)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { openReportList() }
        }
        val docsBtn = TextView(this).apply {
            text = "资料"
            textSize = 12f
            setTextColor(C.DIM)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { openDocs() }
        }
        val gear = TextView(this).apply {
            text = "设置"
            textSize = 12f
            setTextColor(C.DIM)
            setPadding(dp(2), dp(8), dp(4), dp(8))
            setOnClickListener { openSettings() }
        }
        header.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(modeChip)
        header.addView(reportBtn)
        header.addView(docsBtn)
        header.addView(gear)

        val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1) 面试官提问
        val qCard = card()
        qCard.addView(label("面试官提问"))
        questionView = TextView(this).apply {
            text = if (assistMode) "等待面试官提问…" else "点击开始答题，面试官将提问"
            textSize = 19f
            setTextColor(C.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(4).toFloat(), 1.25f)
        }
        qCard.addView(questionView)
        cards.addView(qCard, cardLp())

        // 2) 实时转写（辅助模式为迷你灰字）
        transcriptCard = card()
        transcriptCard.addView(label("实时转写"))
        resultView = TextView(this).apply {
            text = resultPlaceholder
            textSize = if (assistMode) 12f else 14f
            setTextColor(if (assistMode) C.DIM else C.BODY)
            setLineSpacing(dp(5).toFloat(), 1.4f)
            setTextIsSelectable(true)
        }
        transcriptCard.addView(resultView)
        cards.addView(transcriptCard, cardLp())

        // 3) 开场话术（可直接说出口）
        strategyView = TextView(this).apply {
            text = "提问后 3 秒内给出可直接说出口的开场话术。"
            textSize = 18f
            setTextColor(C.TEXT)
            setLineSpacing(dp(6).toFloat(), 1.45f)
            setTextIsSelectable(true)
        }
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val strategyCard = card()
        strategyCard.addView(label("开场话术 · 可直接说"))
        strategyCard.addView(strategyView)
        strategyCard.addView(chipsRow)
        cards.addView(strategyCard, cardLp())

        // 4) 预判追问
        nextView = TextView(this).apply {
            text = "—"
            textSize = 13f
            setTextColor(C.BODY)
            setLineSpacing(dp(4).toFloat(), 1.4f)
        }
        nextCard = card()
        nextCard.addView(label("预判追问"))
        nextCard.addView(nextView)
        nextCard.visibility = View.GONE
        cards.addView(nextCard, cardLp())

        // 4.5) 空闲引导卡（填满中部空白，说明三步流程）
        val hintBody = TextView(this).apply {
            text = "点「开始答题」→ 面试官语音提问 → 3 秒内给出可直接说出口的话术并自动播报。\n" +
                "真人面试时切到「真人辅助」，改成耳机里听提示。"
            textSize = 13f
            setTextColor(C.BODY)
            setLineSpacing(dp(4).toFloat(), 1.5f)
        }
        hintCard = card()
        hintCard.addView(label("怎么用"))
        hintCard.addView(hintBody)
        cards.addView(hintCard, cardLp())

        // 5) 复盘
        feedbackView = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(C.BODY)
            setLineSpacing(dp(4).toFloat(), 1.45f)
            setTextIsSelectable(true)
        }
        feedbackCard = card()
        feedbackCard.addView(label("本轮复盘"))
        feedbackCard.addView(feedbackView)
        feedbackCard.visibility = View.GONE
        feedbackCard.setOnClickListener { lastReportId?.let { openReport(it) } }
        cards.addView(feedbackCard, cardLp())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(cards, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // 状态条：波形 + 收音状态 + 播报进度
        val statusStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        waveView = WaveView(this)
        statusView = TextView(this).apply {
            text = "就绪"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(C.DIM)
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), 0, 0, 0)
        }
        speakStateView = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(C.ROSE_DEEP)
        }
        statusStrip.addView(waveView, LinearLayout.LayoutParams(dp(58), dp(26)))
        statusStrip.addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusStrip.addView(speakStateView)

        // 底部按钮
        btnStart = TextView(this).apply {
            text = "开始答题"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(C.ROSE, null, 22)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(17), 0, dp(17))
            setOnClickListener { onStartStopClicked() }
        }
        val btnSpeak = TextView(this).apply {
            text = "重新播报"
            textSize = 15f
            setTextColor(C.ACCENT_SOFT)
            gravity = Gravity.CENTER
            background = rounded(C.CARD, C.STROKE, 22)
            setPadding(dp(14), dp(17), dp(14), dp(17))
            setOnClickListener { speakCurrentStrategy() }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val speakLp = LinearLayout.LayoutParams(dp(118), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(10)
        }
        btnRow.addView(btnSpeak, speakLp)
        btnRow.addView(btnStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(statusStrip)
        root.addView(btnRow)
        setContentView(root)
    }

    private fun refreshModeUi() {
        modeChip.text = if (assistMode) "真人辅助" else "练习模式"
        resultView.textSize = if (assistMode) 12f else 14f
        resultView.setTextColor(if (assistMode) C.DIM else C.BODY)
        questionView.text = if (assistMode) "等待面试官提问…" else "点击开始答题，面试官将提问"
        resultView.text = resultPlaceholder
        strategyView.text = if (assistMode)
            "听到提问后自动生成可直接说出口的话术并播报。"
        else "提问后 3 秒内给出可直接说出口的开场话术。"
        nextCard.visibility = View.GONE
        feedbackCard.visibility = View.GONE
        speakStateView.text = ""
        status("就绪")
    }

    private fun toggleMode() {
        assistMode = !assistMode
        Prefs.setMode(this, if (assistMode) Prefs.MODE_ASSIST else Prefs.MODE_PRACTICE)
        refreshModeUi()
        log("切换模式: ${if (assistMode) "真人辅助" else "练习模式"}")
    }

    // ---------- F2 资料库（导入 / 查看 / 删除 / 检索） ----------
    private fun openDocs() {
        val pad = dp(18)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(18), pad, dp(14))
            background = rounded(C.CARD, C.STROKE, 24)
        }
        val docs = DocumentStore.list(this)
        box.addView(TextView(this).apply {
            text = "资料库"
            textSize = 18f
            setTextColor(C.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "${docs.size} 份资料 · 共 ${DocumentStore.totalChars(this@MainActivity)} 字 · 支持 PDF / Word / TXT / 截图"
            textSize = 11f
            setTextColor(C.DIM)
            setPadding(0, dp(4), 0, dp(10))
        })

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (docs.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "还没有资料。导入项目 PDF / Word / 截图后，话术会优先引用里面的真实数据。"
                textSize = 12f
                setTextColor(C.DIM)
                setLineSpacing(dp(3).toFloat(), 1.4f)
                setPadding(0, dp(6), 0, dp(10))
            })
        } else {
            docs.forEach { d ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(9), 0, dp(9))
                }
                row.addView(TextView(this).apply {
                    text = d.name
                    textSize = 13f
                    setTextColor(C.TEXT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = "${d.chars}字"
                    textSize = 11f
                    setTextColor(C.DIM)
                    setPadding(dp(8), 0, dp(10), 0)
                })
                row.addView(TextView(this).apply {
                    text = "删除"
                    textSize = 11f
                    setTextColor(C.ROSE_DEEP)
                    setPadding(dp(6), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        DocumentStore.delete(this@MainActivity, d.id)
                        log("资料删除: ${d.name}")
                    }
                })
                listBox.addView(row)
            }
        }
        box.addView(listBox)

        val importBtn = TextView(this).apply {
            text = "＋ 导入资料（PDF / Word / TXT / 截图）"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(C.ACCENT, null, 16)
            setPadding(0, dp(13), 0, dp(13))
            setOnClickListener { pickDocument() }
        }
        box.addView(importBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        val close = TextView(this).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(C.DIM)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
            setOnClickListener { }
        }
        box.addView(close)

        val dlg = AlertDialog.Builder(this).setView(box).create()
        close.setOnClickListener { dlg.dismiss() }
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            dlg.window?.setLayout(dp(350), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    private fun pickDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain",
                "image/*"
            ))
        }
        try {
            startActivityForResult(intent, REQ_DOC)
        } catch (e: Exception) {
            log("打开文件选择器失败: ${e.message}")
            status("无法打开文件选择器")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_DOC || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val docName = queryDisplayName(uri)
        log("开始导入资料: $docName")
        status("解析资料中…")
        Thread {
            try {
                val parsed = DocumentParser.parse(this, uri, docName)
                if (parsed.text.isBlank()) {
                    handler.post {
                        log("资料解析为空: $docName method=${parsed.method}")
                        status("解析失败：未提取到文字")
                    }
                    return@Thread
                }
                val doc = DocumentStore.add(this, docName, parsed.method, parsed.text)
                handler.post {
                    if (doc == null) {
                        status("资料保存失败")
                        return@post
                    }
                    log("资料导入成功: ${doc.name} | 方式=${parsed.method} 页数=${parsed.pages} 字数=${doc.chars}")
                    status("资料已入库：${doc.name}（${doc.chars}字）")
                    openDocs()
                }
            } catch (e: Exception) {
                handler.post {
                    log("资料导入失败: ${e.javaClass.simpleName} ${e.message}")
                    status("资料导入失败: ${e.message}")
                }
            }
        }.apply { isDaemon = true; name = "doc-parse" }.start()
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            } ?: uri.lastPathSegment ?: "资料"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "资料"
        }
    }

    // ---------- 设置弹窗（阈值滑杆 / 播报开关） ----------
    private fun openSettings() {
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(18), pad, dp(14))
            background = rounded(C.CARD, C.STROKE, 24)
        }
        box.addView(TextView(this).apply {
            text = "设置"
            textSize = 18f
            setTextColor(C.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(14))
        })

        box.addView(label("面试官提问过滤阈值"))
        val valueView = TextView(this).apply {
            text = String.format(Locale.US, "相似度 ≥ %.2f", Prefs.threshold(this@MainActivity))
            textSize = 15f
            setTextColor(C.ACCENT_SOFT)
            typeface = Typeface.MONOSPACE
        }
        val seek = SeekBar(this).apply {
            max = 60
            progress = ((Prefs.threshold(this@MainActivity) - Prefs.MIN_THRESHOLD) /
                (Prefs.MAX_THRESHOLD - Prefs.MIN_THRESHOLD) * 60).toInt()
            progressTintList = ColorStateList.valueOf(C.ACCENT)
            thumbTintList = ColorStateList.valueOf(C.ACCENT)
        }
        val hint = TextView(this).apply {
            text = "越高越严格：只过滤与题目高度重合的句子；越低越激进，可能误伤候选人复述题目。"
            textSize = 11f
            setTextColor(C.DIM)
            setLineSpacing(dp(3).toFloat(), 1.35f)
            setPadding(0, dp(4), 0, dp(12))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = Prefs.MIN_THRESHOLD + (Prefs.MAX_THRESHOLD - Prefs.MIN_THRESHOLD) * p / 60f
                valueView.text = String.format(Locale.US, "相似度 ≥ %.2f", v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        box.addView(valueView)
        box.addView(seek)
        box.addView(hint)

        val speakSwitch = android.widget.Switch(this).apply {
            text = "话术自动播报"
            textSize = 14f
            setTextColor(C.TEXT)
            isChecked = Prefs.speakEnabled(this@MainActivity)
            setPadding(0, dp(6), 0, dp(12))
        }
        box.addView(speakSwitch)

        // F6：知识库 / 隐私 / 关于 分组入口
        fun entry(title: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(C.TEXT)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
        }
        box.addView(entry("资料库 / 知识库（${DocumentStore.list(this).size} 份）") {
            dlgRef?.dismiss()
            openDocs()
        })
        box.addView(entry("隐私说明") {
            AlertDialog.Builder(this)
                .setTitle("隐私说明")
                .setMessage("· 录音、转写文本、资料解析结果、复盘报告只存在本机（应用私有目录），不上传除 DeepSeek / 讯飞转写以外的任何服务器。\n" +
                    "· 讯飞实时转写会把音频实时发送到讯飞云用于识别；DeepSeek 只接收文字（问题、你的回答、资料检索片段）用于生成话术与复盘。\n" +
                    "· 密钥通过 local.properties 注入，不进代码仓库；本应用无账号体系、无埋点。")
                .setPositiveButton("知道了", null)
                .show()
        })
        box.addView(entry("关于") {
            AlertDialog.Builder(this)
                .setTitle("面试博弈助手")
                .setMessage("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n\n" +
                    "练习模式：App 当面试官，练话术；真人辅助：听真人提问给提示。\n" +
                    "话术与复盘由 DeepSeek 生成，转写由讯飞 rtasr_llm 提供。")
                .setPositiveButton("好", null)
                .show()
        })

        val done = TextView(this).apply {
            text = "保存"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(C.ACCENT, null, 18)
            setPadding(0, dp(13), 0, dp(13))
            setOnClickListener { }
        }
        box.addView(done)

        val dlg = AlertDialog.Builder(this).setView(box).create()
        dlgRef = dlg
        done.setOnClickListener {
            val v = Prefs.MIN_THRESHOLD + (Prefs.MAX_THRESHOLD - Prefs.MIN_THRESHOLD) * seek.progress / 60f
            Prefs.setThreshold(this, v)
            Prefs.setSpeakEnabled(this, speakSwitch.isChecked)
            log(String.format(Locale.US, "设置已保存: 阈值=%.2f 播报=%s", Prefs.threshold(this), Prefs.speakEnabled(this)))
            dlg.dismiss()
        }
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            dlg.window?.setLayout(dp(340), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    /** F5 六维雷达图：6 轴六边形网格 + 分数多边形 */
    private class RadarView(ctx: Context, private val dims: List<ReportEngine.Dim>) : View(ctx) {
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x33A9A29A
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 2f
            color = 0x26A9A29A
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x558FA69B
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0xFF8FA69B.toInt()
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F7A6E.toInt() }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            color = 0xFF6E6A64.toInt()
        }
        private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            color = 0xFF5F7A6E.toInt()
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            val n = dims.size.coerceAtLeast(3)
            val cx = width / 2f
            val cy = height / 2f + 4
            val radius = minOf(width, height) / 2f - 56f
            val step = Math.PI * 2 / n

            // 网格 4 层
            for (layer in 1..4) {
                val r = radius * layer / 4f
                val path = android.graphics.Path()
                for (i in 0 until n) {
                    val a = -Math.PI / 2 + step * i
                    val x = cx + (r * Math.cos(a)).toFloat()
                    val y = cy + (r * Math.sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, gridPaint)
            }
            // 轴线
            for (i in 0 until n) {
                val a = -Math.PI / 2 + step * i
                canvas.drawLine(cx, cy,
                    cx + (radius * Math.cos(a)).toFloat(), cy + (radius * Math.sin(a)).toFloat(), axisPaint)
            }
            // 分数多边形
            val fp = android.graphics.Path()
            for (i in 0 until n) {
                val v = (dims[i].score.coerceIn(0, 100)) / 100f
                val a = -Math.PI / 2 + step * i
                val x = cx + (radius * v * Math.cos(a)).toFloat()
                val y = cy + (radius * v * Math.sin(a)).toFloat()
                if (i == 0) fp.moveTo(x, y) else fp.lineTo(x, y)
            }
            fp.close()
            canvas.drawPath(fp, fillPaint)
            canvas.drawPath(fp, linePaint)
            // 顶点 + 标签
            for (i in 0 until n) {
                val a = -Math.PI / 2 + step * i
                val v = (dims[i].score.coerceIn(0, 100)) / 100f
                val px = cx + (radius * v * Math.cos(a)).toFloat()
                val py = cy + (radius * v * Math.sin(a)).toFloat()
                canvas.drawCircle(px, py, 5f, dotPaint)
                val lx = cx + ((radius + 34f) * Math.cos(a)).toFloat()
                val ly = cy + ((radius + 34f) * Math.sin(a)).toFloat()
                val text = dims[i].name
                val tw = labelPaint.measureText(text)
                canvas.drawText(text, lx - tw / 2, ly + 8f, labelPaint)
                val sText = dims[i].score.toString()
                canvas.drawText(sText, lx - scorePaint.measureText(sText) / 2, ly + 36f, scorePaint)
            }
        }
    }

    /** 简易音量波形：随录音 RMS 更新 */
    private class WaveView(ctx: Context) : View(ctx) {
        private val bars = FloatArray(14) { 0.18f }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8FA69B.toInt() }
        fun push(rms: Double) {
            val v = (rms / 5000.0).toFloat().coerceIn(0.12f, 1f)
            for (i in bars.size - 1 downTo 1) bars[i] = bars[i - 1]
            bars[0] = v
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat() / bars.size
            val h = height.toFloat()
            for (i in bars.indices) {
                val bh = (bars[i] * h).coerceAtLeast(3f)
                val left = i * w + 1f
                val top = (h - bh) / 2f
                paint.color = if (bars[i] > 0.55f) 0xFF8FA69B.toInt() else 0xFFC3CFC8.toInt()
                canvas.drawRoundRect(RectF(left, top, left + w * 0.55f, top + bh), 2f, 2f, paint)
            }
        }
    }

    private fun onStartStopClicked() {
        if (transcribing) stopTranscribe("用户点击停止")
        else startTranscribe()
    }

    private fun setButtonRunning(running: Boolean) {
        handler.post {
            btnStart.text = if (running) "停止答题" else "开始答题"
            hintCard.visibility = if (running) View.GONE else hintCard.visibility
            btnStart.background = rounded(if (running) C.ROSE_DEEP else C.ROSE, null, 22)
            btnStart.setTextColor(0xFFFFFFFF.toInt())
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
            log(String.format(Locale.US, "过滤面试官提问句(阈值%.2f, bg=${bg}ms ed=${ed}ms): $t", Prefs.threshold(this)))
            return
        }
        committedText.append(t)
        if (assistMode) {
            if (looksLikeQuestion(t)) {
                log("识别到提问: $t → 800ms 后生成话术")
                handler.post { questionView.text = t }
                scheduleStrategy(t, 800)
            } else {
                // 候选人回答期间若有停顿，也允许基于上下文补一版话术（同一问题 30s 内去重）
                handler.removeCallbacks(strategyDebounce)
            }
        }
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
        val th = Prefs.threshold(this)
        return sim >= th
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
            feedbackCard.visibility = View.VISIBLE
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
                    tts?.setSpeechRate(1.1f) // 播报 24 字控制在 5 秒内
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
                if (utteranceId?.startsWith("strategy_") == true) return
                ttsStartMs = SystemClock.elapsedRealtime() - recordStartElapsed
                log("TTS 播题开始 @${ttsStartMs}ms（计入面试官提问过滤时间窗）")
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith("strategy_") == true) {
                    val cost = SystemClock.elapsedRealtime() - speakStartAt
                    log("话术播报完成，用时 ${cost}ms")
                    handler.post { speakStateView.text = "" }
                    return
                }
                ttsEndMs = SystemClock.elapsedRealtime() - recordStartElapsed
                log("TTS 播题完成 @${ttsEndMs}ms")
                // 题目播完 → 生成博弈话术（真人辅助模式由转写里的提问触发）
                scheduleStrategy(lastQuestion, 200)
            }
            override fun onError(utteranceId: String?) { log("TTS 错误: $utteranceId") }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) { log("TTS 错误 code=$errorCode") }
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
                    val rmsNow = lastRms
                    handler.post { waveView.push(rmsNow) }
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
        Conversation.clear()
        setButtonRunning(true)
        // 前台服务保活：ColorOS 后台冻结会导致推流线程卡死
        try { startService(Intent(this, DiagService::class.java)) } catch (e: Exception) { log("前台服务启动失败: ${e.message}") }
        // 练习模式：TTS 播题 + 录音同时进行；真人辅助模式：只收音，不播题
        initTtsIfNeeded()
        if (!assistMode) handler.postDelayed({ speakQuestion() }, 600)

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
        Conversation.recordAnswer(resultText())
        log("转写文本: ${committedText.length}字（过滤提问句 $droppedSentences 句）")
        // 基于转写结果生成面试反馈（独立线程；等 result 下行收敛后触发）
        handler.postDelayed({ generateFeedback() }, 800)
        // F5：六维复盘（并行，落库后可从复盘卡进入）
        handler.postDelayed({ generateReport() }, 1400)
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

    // ---------- F5 六维复盘 ----------
    private fun generateReport() {
        val q = lastQuestion
        val a = resultText()
        status("生成六维复盘…")
        log("开始生成复盘: 题目=$q | 回答${a.length}字")
        Thread {
            val r = ReportEngine.generate(q, a, DEEPSEEK_API_KEY, Conversation.reportContext())
            if (r.error.isNotEmpty()) {
                handler.post {
                    log("复盘生成失败: ${r.error}")
                    status("复盘生成失败")
                }
                return@Thread
            }
            val item = ReportStore.save(this, q, a, r)
            handler.post {
                lastReportId = item?.id
                val avg = item?.avgScore ?: 0
                log("复盘已生成: 均分=$avg 维度=${r.dims.joinToString { "${it.name}${it.score}" }}")
                appendFeedback("\n\n▸ 六维复盘已生成（均分 $avg），点此卡片查看")
            }
        }.apply { isDaemon = true; name = "report-gen" }.start()
    }

    private fun openReportList() {
        val items = ReportStore.list(this)
        val pad = dp(18)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(18), pad, dp(12))
            background = rounded(C.CARD, C.STROKE, 24)
        }
        box.addView(TextView(this).apply {
            text = "复盘记录"
            textSize = 18f
            setTextColor(C.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = if (items.isEmpty()) "还没有复盘记录" else "共 ${items.size} 份 · 报告只基于你的真实回答"
            textSize = 11f
            setTextColor(C.DIM)
            setPadding(0, dp(4), 0, dp(10))
        })
        val dlg = AlertDialog.Builder(this).setView(box).create()
        if (items.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "答完一轮后会自动生成六维复盘，包括内容完整度、数据可信度、逻辑自洽、表达结构、追问防御、时间控制。"
                textSize = 12f
                setTextColor(C.DIM)
                setLineSpacing(dp(3).toFloat(), 1.4f)
            })
        } else {
            items.take(12).forEach { item ->
                box.addView(TextView(this).apply {
                    text = "${item.question.take(18)}  ·  均分 ${item.avgScore}"
                    textSize = 13f
                    setTextColor(C.TEXT)
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener {
                        dlg.dismiss()
                        openReport(item.id)
                    }
                })
            }
        }
        box.addView(TextView(this).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(C.DIM)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { dlg.dismiss() }
        })
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            dlg.window?.setLayout(dp(350), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    private fun openReport(id: String) {
        val data = ReportStore.load(this, id)
        if (data == null) {
            status("复盘不存在")
            return
        }
        val (q, answer, rep) = data
        if (rep == null) {
            status("复盘解析失败")
            return
        }
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(16), pad, dp(12))
            background = rounded(C.CARD, C.STROKE, 24)
        }
        box.addView(TextView(this).apply {
            text = "六维复盘"
            textSize = 18f
            setTextColor(C.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = q
            textSize = 12f
            setTextColor(C.DIM)
            setPadding(0, dp(4), 0, dp(4))
        })
        // 六维雷达图
        box.addView(RadarView(this, rep.dims).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230))
        })
        rep.dims.forEach { d ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(TextView(this).apply {
                text = d.name
                textSize = 13f
                setTextColor(C.TEXT)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(TextView(this).apply {
                text = "${d.score}"
                textSize = 13f
                setTextColor(C.ACCENT_SOFT)
                typeface = Typeface.DEFAULT_BOLD
            })
            row.addView(head)
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 100f
                setPadding(0, dp(4), 0, dp(3))
            }
            bar.addView(View(this).apply { background = rounded(C.ACCENT, null, 4) },
                LinearLayout.LayoutParams(0, dp(6), d.score.toFloat().coerceAtLeast(1f)))
            bar.addView(View(this).apply { background = rounded(0xFFEDE8E0.toInt(), null, 4) },
                LinearLayout.LayoutParams(0, dp(6), (100 - d.score).toFloat().coerceAtLeast(1f)))
            row.addView(bar)
            row.addView(TextView(this).apply {
                text = d.comment
                textSize = 11f
                setTextColor(C.BODY)
                setLineSpacing(dp(3).toFloat(), 1.35f)
            })
            box.addView(row)
        }
        if (rep.strengths.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "亮点：${rep.strengths.joinToString("；")}"
                textSize = 12f
                setTextColor(C.BODY)
                setLineSpacing(dp(3).toFloat(), 1.4f)
                setPadding(0, dp(8), 0, dp(2))
            })
        }
        if (rep.improvements.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "改进：${rep.improvements.joinToString("；")}"
                textSize = 12f
                setTextColor(C.BODY)
                setLineSpacing(dp(3).toFloat(), 1.4f)
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        if (rep.verdict.isNotBlank()) {
            box.addView(TextView(this).apply {
                text = "总评：${rep.verdict}"
                textSize = 12f
                setTextColor(C.ACCENT_SOFT)
                setPadding(0, dp(6), 0, 0)
            })
        }
        box.addView(TextView(this).apply {
            text = "你的原话：${answer.take(60)}${if (answer.length > 60) "…" else ""}"
            textSize = 10f
            setTextColor(C.LABEL)
            setPadding(0, dp(8), 0, 0)
        })
        val dlg = AlertDialog.Builder(this).setView(android.widget.ScrollView(this).apply { addView(box) }).create()
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            dlg.window?.setLayout(dp(360), dp(560))
        }
        dlg.show()
    }

    // ---------- 博弈话术：生成 + 播报 ----------
    private var strategyJob = 0
    private var lastStrategyQuestion = ""
    private var lastStrategyAt = 0L
    private var currentStrategy = ""
    private var pendingQuestion = ""
    private var speakStartAt = 0L
    private val strategyDebounce = Runnable { generateStrategy(pendingQuestion) }

    private val speakTicker = object : Runnable {
        override fun run() {
            val sec = (SystemClock.elapsedRealtime() - speakStartAt) / 1000
            speakStateView.text = String.format(Locale.US, "VOICE %02d / 05s", sec.coerceAtMost(5))
            if (sec >= 5) {
                handler.postDelayed({ speakStateView.text = "" }, 1000)
                return
            }
            handler.postDelayed(this, 200)
        }
    }

    private fun onDebugQuestion(q: String) {
        log("DEBUG 注入提问: $q")
        questionView.text = q
        scheduleStrategy(q, 200)
    }

    private fun looksLikeQuestion(s: String): Boolean {
        val q = s.trim()
        if (q.length < 4) return false
        if (q.endsWith("？") || q.endsWith("?")) return true
        return arrayOf("吗", "多少", "怎么", "为什么", "如何", "是否", "能不能", "有没有", "哪", "几")
            .any { q.contains(it) }
    }

    private fun scheduleStrategy(question: String, delayMs: Long = 800) {
        if (question.isBlank()) return
        pendingQuestion = question
        handler.removeCallbacks(strategyDebounce)
        handler.postDelayed(strategyDebounce, delayMs)
    }

    private fun generateStrategy(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (q == lastStrategyQuestion && now - lastStrategyAt < 30_000) {
            log("同一问题 30s 内已生成话术，跳过: $q")
            return
        }
        lastStrategyQuestion = q
        lastStrategyAt = now
        val job = ++strategyJob
        status("生成话术…")
        log("开始生成话术: $q")
        val t0 = SystemClock.elapsedRealtime()
        Thread {
            val rag = DocumentStore.retrieve(this, q)
            val corpusTips = matchCorpus(q, "")
            val history = Conversation.contextPrompt()
            val tips = rag + corpusTips
            if (rag.isNotEmpty()) log("资料库检索命中 ${rag.length} 字（问题: $q）")
            if (history.isNotEmpty()) log("多轮上下文: 已有 ${Conversation.size()} 轮，注入 ${history.length} 字")
            val advice = StrategyEngine.generate(q, tips, DEEPSEEK_API_KEY, history)
            handler.post {
                if (job != strategyJob) return@post
                if (advice.error.isNotEmpty()) {
                    log("话术生成失败: ${advice.error}")
                    strategyView.text = "话术生成失败：${advice.error.take(60)}"
                    status("话术生成失败")
                    return@post
                }
                val total = SystemClock.elapsedRealtime() - t0
                log("话术就绪: 模型${advice.elapsedMs}ms / 总${total}ms | SAY=${advice.opening} | NEXT=${advice.followup}")
                Conversation.addAdvice(q, advice.opening)
                currentStrategy = advice.opening
                strategyView.text = "「${advice.opening}」"
                renderChips(advice.opening)
                if (advice.followup.isNotBlank()) {
                    nextView.text = advice.followup
                    nextCard.visibility = View.VISIBLE
                }
                status(if (assistMode) "收音中" else "答题中")
                if (Prefs.speakEnabled(this@MainActivity)) speakStrategy(advice.opening)
            }
        }.apply { isDaemon = true; name = "strategy-gen" }.start()
    }

    private fun renderChips(content: String) {
        chipsRow.removeAllViews()
        val re = Regex("""(\d+(?:\.\d+)?\s*(?:万|亿|%|个|天|家|倍))""")
        val found = re.findAll(content).map { it.value }.distinct().take(3).toList()
        for (f in found) {
            chipsRow.addView(TextView(this).apply {
                text = f
                textSize = 11f
                setTextColor(C.BODY)
                setPadding(dp(11), dp(6), dp(11), dp(6))
                background = rounded(0xFFF0EDE6.toInt(), null, 999)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        chipsRow.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun speakCurrentStrategy() {
        val s = currentStrategy.ifBlank { strategyView.text.toString().trim('「', '」', ' ') }
        if (s.isBlank() || s.startsWith("话术生成失败")) {
            status("暂无可播报话术")
            return
        }
        speakStrategy(s)
    }

    private fun speakStrategy(text: String) {
        if (!ttsReady) {
            log("TTS 未就绪，话术仅上屏")
            return
        }
        val say = StrategyEngine.speechText(text)
        speakStartAt = SystemClock.elapsedRealtime()
        tts?.speak(say, TextToSpeech.QUEUE_FLUSH, null, "strategy_$speakStartAt")
        log("播报话术(${say.length}字): $say")
        handler.removeCallbacks(speakTicker)
        handler.post(speakTicker)
    }

    /** 已在前台时再带 extra 拉起（adb am start -f 0x20000000）：直接驱动调试注入，便于连续多轮 */
    @Deprecated("Deprecated in Java")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra("debug_question")?.takeIf { it.isNotBlank() }?.let { q ->
            assistMode = true
            refreshModeUi()
            onDebugQuestion(q)
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
