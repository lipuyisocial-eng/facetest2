package com.xingyihui.interviewassistant.diag

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.sqrt

/**
 * 面试博弈助手 · 最小音频诊断 APK（规范 01 §6）
 *
 * Test A  麦克风录音：权限 / 输入设备 / 采样率 / RMS / 10s 录制到私有目录 / AudioTrack 回放
 * Test B  本机 TTS：初始化 / 中文可用性 / 固定文本 / 回调打点
 * Test C  同时录音 + TTS：录音不中断、路由/音量异常观察
 * Test D  稳定性：2 / 5 / 10 / 30 分钟持续录音，记录中断 / 崩溃 / 内存
 *
 * 本工程为诊断阶段，不接讯飞 / DeepSeek / 知识库；不含任何密钥。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "InterviewDiag"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val REQ_RECORD_AUDIO = 100
        private const val TTS_UTTER_ID = "diag_tts"
        private const val TEST_A_DURATION_MS = 10_000L

        // Test E：讯飞实时转写（联调临时决策：用户确认密钥入 APK；正式发布版必须移除并改走服务端代理）
        private const val XF_APP_ID = "6eb32081"
        private const val XF_API_KEY = "9f15c5590c9bcb7ed781a8a6a4f61281"
        private const val XF_WS_URL = "wss://rtasr.xfyun.cn/v1/ws"
        private const val E_DURATION_MS = 15_000L

        private val STABILITY_OPTIONS = longArrayOf(2, 5, 10, 30)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private lateinit var logView: TextView
    private lateinit var statusView: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsLangOk = false

    private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false
    private var recordThread: Thread? = null
    private var recordBytes = 0
    private var recordStartElapsed = 0L
    private var readErrors = 0
    private var lastRms = 0.0
    private var recordFile: File? = null
    private var recordStream: FileOutputStream? = null

    private var stabilitySec = 0
    private var stabilityTargetSec = 0
    private val stabilityTick = object : Runnable {
        override fun run() {
            if (recording) {
                stabilitySec++
                val memMb = Runtime.getRuntime().totalMemory() / 1024 / 1024
                status("稳定性录音 ${stabilitySec}s / 目标 ${stabilityTargetSec}s | RMS=${"%.1f".format(lastRms)} | 内存≈${memMb}MB")
                if (stabilitySec >= stabilityTargetSec) {
                    stopRecording("达到目标时长 ${stabilityTargetSec}s")
                    return
                }
                handler.postDelayed(this, 1000)
            }
        }
    }

    // Test E：讯飞实时转写状态
    private var ws: WebSocket? = null
    private var wsClient: OkHttpClient? = null
    @Volatile private var eRunning = false
    private var eStopPosted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        log("应用启动 v0.1.0（音频诊断版）")
        log("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        log("音频配置: ${SAMPLE_RATE}Hz / 单声道 / 16bit PCM / 40ms/帧(1280B)")
        checkRecordPermission()
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        statusView = TextView(this).apply {
            text = "状态: 就绪"
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }

        logView = TextView(this).apply {
            textSize = 12f
            setBackgroundColor(0xFF101418.toInt())
            setTextColor(0xFFE8F0F8.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(this).apply {
            addView(logView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(340)))
        }

        root.addView(statusView)
        root.addView(scroll)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn("A 录音10s") { runTestA() }, lp(1f))
        row1.addView(btn("B TTS播报") { runTestB() }, lp(1f))
        row1.addView(btn("C 同时录播") { runTestC() }, lp(1f))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("D 稳定性") { startStability() }, lp(1f))
        row2.addView(btn("停止") { stopRecording("用户手动停止") }, lp(1f))
        root.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (min in STABILITY_OPTIONS) {
            row3.addView(btn("D-${min}分") {
                stabilityTargetSec = (min * 60).toInt()
                startStability()
            }, lp(1f))
        }
        root.addView(row3)

        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(btn("E 讯飞转写") { runTestE() }, lp(1f))
        row4.addView(btn("E 立即结束") { stopTestE("用户手动结束") }, lp(1f))
        root.addView(row4)

        setContentView(root)
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        setOnClickListener { onClick() }
    }

    private fun lp(weight: Float) = LinearLayout.LayoutParams(0, dp(52), weight).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
        topMargin = dp(6)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- 日志 ----------
    private fun stamp() = timeFmt.format(Date())

    private fun log(msg: String) {
        Log.i(TAG, msg)
        handler.post {
            logView.append("[${stamp()}] $msg\n")
            (logView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun status(msg: String) {
        handler.post { statusView.text = "状态: $msg" }
    }

    // ---------- 权限 ----------
    private fun checkRecordPermission() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
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
            } else {
                log("RECORD_AUDIO 被拒绝 — Test A/C/D 无法执行")
                status("麦克风权限被拒绝")
            }
        }
    }

    // ---------- 录音 ----------
    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun createRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        log("AudioRecord 最小 buffer=$minBuf B")
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

    private fun deviceInfo(): String {
        val dev = audioRecord?.routedDevice
        return if (dev != null) "${dev.productName ?: "未知"} (type=${dev.type})"
        else "未取到路由设备"
    }

    private fun startRecording(tag: String, saveToFile: Boolean, onFrame: ((ByteArray, Int) -> Unit)? = null): Boolean {
        if (!hasMicPermission()) {
            log("[$tag] 无麦克风权限，终止")
            return false
        }
        if (recording) {
            log("[$tag] 已在录音中，忽略重复启动")
            return false
        }
        val rec = createRecorder() ?: return false
        audioRecord = rec
        recordBytes = 0
        readErrors = 0
        lastRms = 0.0
        recordStartElapsed = SystemClock.elapsedRealtime()

        var stream: FileOutputStream? = null
        if (saveToFile) {
            val f = File(filesDir, "diag_${System.currentTimeMillis()}_${tag}.pcm")
            try {
                stream = FileOutputStream(f)
                recordFile = f
                log("[$tag] 录音将保存到: ${f.absolutePath}")
            } catch (e: Exception) {
                log("[$tag] 无法创建录音文件: ${e.message}")
            }
        }
        recordStream = stream

        rec.startRecording()
        recording = true
        log("[$tag] 录音启动 @${SAMPLE_RATE}Hz，输入设备: ${deviceInfo()}")
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
                    readErrors++
                    log("[$tag] read 返回错误码 $n（累计 $readErrors 次）")
                }
            }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    private fun stopRecording(reason: String) {
        if (!recording && recordStream == null) {
            log("当前未在录音")
            return
        }
        val wasRecording = recording
        recording = false
        recordThread?.join(1500)
        recordThread = null
        try {
            recordStream?.flush()
            recordStream?.close()
        } catch (e: Exception) {
            log("关闭录音文件异常: ${e.message}")
        }
        recordStream = null

        val rec = audioRecord
        if (rec != null) {
            try {
                rec.stop()
            } catch (e: IllegalStateException) {
                log("AudioRecord stop 异常: ${e.message}")
            }
            rec.release()
        }
        audioRecord = null
        if (wasRecording) {
            val durMs = SystemClock.elapsedRealtime() - recordStartElapsed
            log("录音停止: $reason | 时长=${"%.1f".format(durMs / 1000.0)}s | 采集=${recordBytes}B | 读错误=$readErrors")
        }
    }

    // ---------- Test A：录音 10s + 保存 + 回放 ----------
    private fun runTestA() {
        if (!startRecording("TestA", saveToFile = true)) return
        status("Test A 录音中 10s…")
        handler.postDelayed({
            stopRecording("TestA 10s 完成")
            playbackPcm()
        }, TEST_A_DURATION_MS)
    }

    private fun playbackPcm() {
        val f = recordFile
        if (f == null || !f.exists() || f.length() == 0L) {
            log("Test A 回放跳过：无录音文件")
            return
        }
        val data = f.readBytes()
        log("Test A 回放：文件 ${f.name} 共 ${data.size}B，AudioTrack 播放中…")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                // 播放必须用输出声道掩码 CHANNEL_OUT_MONO；误用录音输入掩码 CHANNEL_IN_MONO(16) 会导致 build() 抛 UnsupportedOperationException
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(ENCODING)
                .build())
            .setBufferSizeInBytes(data.size.coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(data, 0, data.size)
        // MODE_STATIC：build 后 state=STATE_NO_STATIC_DATA(2)，write 数据后才变 STATE_INITIALIZED(1)；
        // 初始化检查必须放在 write 之后，否则会误判跳过回放
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            log("Test A AudioTrack 初始化失败 state=${track.state}（MODE_STATIC write 后应为 1）")
            track.release()
            return
        }
        log("Test A 回放开始 → 请人工确认扬声器是否真实发声")
        track.play()
        val totalMs = data.size * 1000L / (SAMPLE_RATE * 2)
        handler.postDelayed({
            log("Test A 回放结束检查：playbackHeadPosition=${track.playbackHeadPosition} / 预计${totalMs}ms")
            track.release()
        }, totalMs + 400)
    }

    // ---------- Test B：TTS ----------
    private fun initTtsIfNeeded() {
        if (ttsReady) return
        log("Test B 初始化 TextToSpeech…")
        tts = TextToSpeech(this) { status ->
            handler.post {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    val lang = tts?.setLanguage(Locale.CHINA)
                    ttsLangOk = lang == TextToSpeech.LANG_COUNTRY_AVAILABLE || lang == TextToSpeech.LANG_AVAILABLE
                    log("Test B TTS 初始化成功；setLanguage(zh_CN)=$lang；中文可用=${ttsLangOk}")
                    if (!ttsLangOk) log("Test B 中文语音不可用，需检查系统 TTS 引擎/语音包")
                } else {
                    log("Test B TTS 初始化失败 status=$status")
                }
            }
        }
    }

    private fun runTestB() {
        initTtsIfNeeded()
        if (!ttsReady) {
            log("Test B TTS 未就绪，请稍候重试")
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                log("Test B TTS onStart: 播报开始")
            }
            override fun onDone(utteranceId: String?) {
                log("Test B TTS onDone: 播报完成")
            }
            override fun onError(utteranceId: String?) {
                log("Test B TTS onError: 播报错误")
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) {
                log("Test B TTS onError code=$errorCode")
            }
        })
        val res = tts?.speak("音频测试，语音播报正常。", TextToSpeech.QUEUE_FLUSH, null, TTS_UTTER_ID)
        log("Test B speak() 返回=$res（不代表实际出声，需人工确认扬声器）")
    }

    // ---------- Test C：同时录音 + TTS ----------
    private fun runTestC() {
        if (!startRecording("TestC", saveToFile = true)) return
        status("Test C 录音中，随后触发 TTS…")
        handler.postDelayed({
            log("Test C 录音进行中 ${(SystemClock.elapsedRealtime() - recordStartElapsed) / 1000}s，触发 TTS")
            log("Test C 触发前 AudioRecord state=${audioRecord?.recordingState}")
            runTestB()
            handler.postDelayed({
                val alive = recording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
                log("Test C TTS 后 2s：录音存活=$alive，已采集=${recordBytes}B")
                stopRecording("TestC 检查完成")
                status("Test C 完成")
            }, 2000)
        }, 1500)
    }

    // ---------- Test D：稳定性 ----------
    private fun startStability() {
        if (stabilityTargetSec <= 0) {
            status("请先选择 D-2分/5分/10分/30分")
            return
        }
        if (!startRecording("TestD", saveToFile = false)) return
        stabilitySec = 0
        status("Test D 稳定性录音启动，目标 ${stabilityTargetSec}s")
        handler.post(stabilityTick)
    }

    // ---------- Test E：讯飞实时转写 ----------
    private fun xfSigna(ts: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest((XF_APP_ID + ts).toByteArray(Charsets.UTF_8))
        val md5Hex = md5.joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(XF_API_KEY.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val sig = mac.doFinal(md5Hex.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    private fun runTestE() {
        if (eRunning) {
            log("Test E 已在运行中，忽略重复启动")
            return
        }
        if (!startRecording("TestE", saveToFile = false, onFrame = { buf, n ->
            ws?.send(buf.copyOfRange(0, n).toByteString())
        })) return
        eRunning = true
        eStopPosted = false
        status("Test E 讯飞转写中 15s…")
        val ts = (System.currentTimeMillis() / 1000).toString()
        val signa = xfSigna(ts)
        val url = "$XF_WS_URL?appid=$XF_APP_ID&ts=$ts&signa=$signa&roleType=2"
        log("Test E 连接讯飞 RTASR…（roleType=2 说话人分离）")
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
        wsClient = client
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("Test E WS 连接成功，开始推流")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleXfMessage(bytes.utf8())
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("Test E WS 失败: ${t.message}")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("Test E WS 关闭中 code=$code reason=$reason")
            }
        })
        handler.postDelayed({ stopTestE("TestE 15s 自动结束") }, E_DURATION_MS)
    }

    private fun stopTestE(reason: String) {
        if (!eRunning) {
            log("Test E 未在运行")
            return
        }
        if (eStopPosted) return
        eStopPosted = true
        log("Test E 结束: $reason，发送 end 帧…")
        ws?.send("{\"end\":true}".encodeUtf8())
        ws?.close(1000, "diag end")
        ws = null
        wsClient?.dispatcher?.executorService?.shutdown()
        wsClient = null
        stopRecording(reason)
        eRunning = false
        status("Test E 完成")
    }

    private fun handleXfMessage(json: String) {
        try {
            val root = JSONObject(json)
            when (root.optString("action")) {
                "started" -> log("Test E 讯飞开始识别 code=${root.optInt("code")}")
                "result" -> {
                    val data = root.optJSONObject("data") ?: return
                    val cn = data.optJSONObject("cn") ?: return
                    val st = cn.optJSONObject("st") ?: return
                    val rt = st.optJSONArray("rt") ?: return
                    for (i in 0 until rt.length()) {
                        val seg = rt.getJSONObject(i)
                        val speaker = seg.optInt("speaker", -1)
                        val wsArr = seg.optJSONArray("ws") ?: continue
                        val sb = StringBuilder()
                        for (j in 0 until wsArr.length()) {
                            val w = wsArr.getJSONObject(j)
                            val cw = w.optJSONArray("cw") ?: continue
                            if (cw.length() > 0) sb.append(cw.getJSONObject(0).optString("w", ""))
                        }
                        if (sb.isNotEmpty()) {
                            val who = when (speaker) { 0 -> "面试官"; 1 -> "候选人"; else -> "?" }
                            log("Test E [$who] $sb")
                        }
                    }
                }
                "error" -> log("Test E 讯飞错误 code=${root.optInt("code")} msg=${root.optString("msg")}（10115 不重连）")
                else -> log("Test E 未知消息: $json")
            }
        } catch (e: Exception) {
            log("Test E 解析消息失败: ${e.message}")
        }
    }

    // ---------- 生命周期 ----------
    override fun onPause() {
        super.onPause()
        if (recording) {
            log("onPause：自动停止录音（当前为 Activity 前台模式；后台持续录音需前台服务，后续迭代实现）")
            stopRecording("onPause 自动停止")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recording = false
        handler.removeCallbacksAndMessages(null)
        eRunning = false
        try { ws?.close(1000, "onDestroy") } catch (_: Exception) {}
        wsClient?.dispatcher?.executorService?.shutdown()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
