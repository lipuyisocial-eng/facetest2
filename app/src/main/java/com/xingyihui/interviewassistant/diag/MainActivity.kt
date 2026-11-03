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
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.sqrt

/**
 * 面试博弈助手 · 正式版 v1.0.0
 *
 * 核心链路：点击开始 → 录音（16kHz 单声道 16bit）→ 讯飞 RTASR 实时转写（OkHttp WebSocket，
 * 独立推流线程 40ms 固定节奏）→ 识别文字实时上屏 → 点击停止结束并发送 end 帧。
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

        // 讯飞实时转写（RTASR）密钥 —— 用户确认内置 APK
        private const val XF_APP_ID = "6eb32081"
        private const val XF_API_KEY = "9f15c5590c9bcb7ed781a8a6a4f61281"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var btnStart: TextView

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
    private var sendBytes = 0L
    private val frameQueue = LinkedBlockingQueue<ByteArray>(512)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 转写期间保持应用前台：避免 ColorOS 后台网络限制（实测：后台态推流零 result）
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        buildUi()
        log("应用启动 v1.0.0（正式版·实时转写）")
        log("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
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

        val resultLabel = TextView(this).apply {
            text = "识别结果"
            textSize = 13f
            setTextColor(0xFF8A8F9C.toInt())
            setPadding(0, 0, 0, dp(6))
        }

        resultView = TextView(this).apply {
            text = "点击下方按钮开始答题，你的回答将实时转为文字。"
            textSize = 15f
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.35f)
            setTextColor(0xFFE8F0F8.toInt())
            setBackgroundColor(0xFF14181F.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val scroll = ScrollView(this).apply {
            addView(resultView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))
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
        root.addView(resultLabel)
        root.addView(scroll)
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

    private fun appendResult(text: String) {
        handler.post {
            resultView.text = if (resultView.text.isEmpty() || resultView.text == "点击下方按钮开始答题，你的回答将实时转为文字。")
                text else resultView.text.toString() + text
            (resultView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun log(msg: String) = Log.i(TAG, msg)

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

    // ---------- 讯飞实时转写 ----------
    private fun xfSigna(ts: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest((XF_APP_ID + ts).toByteArray(Charsets.UTF_8))
        val md5Hex = md5.joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(XF_API_KEY.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val sig = mac.doFinal(md5Hex.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP)
    }

    private fun startTranscribe() {
        if (transcribing) return
        transcribing = true
        sendFrames = 0
        sendBytes = 0L
        frameQueue.clear()
        status("准备中…")
        setButtonRunning(true)
        // 前台服务保活：ColorOS 后台冻结会导致推流线程卡死
        try { startService(Intent(this, DiagService::class.java)) } catch (e: Exception) { log("前台服务启动失败: ${e.message}") }

        val ts = (System.currentTimeMillis() / 1000).toString()
        val signa = URLEncoder.encode(xfSigna(ts), "UTF-8") // signa 含 +/=/ 必须 URL 编码，否则 10110 illegal signa
        val path = "/v1/ws?appid=$XF_APP_ID&ts=$ts&signa=$signa&vadMdn=2"
        log("连接讯飞 RTASR…")

        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("wss://rtasr.xfyun.cn$path").build()
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
        okWs?.send("{\"end\":true}".encodeUtf8())
        Thread.sleep(300) // 等 end 帧送达
        okWs?.close(1000, "done")
        okWs = null
        setButtonRunning(false)
        stopService(Intent(this, DiagService::class.java))
        status("转写结束")
        appendResult("\n")
    }

    private fun handleXfMessage(json: String) {
        try {
            val root = JSONObject(json)
            when (root.optString("action")) {
                "started" -> {
                    log("讯飞开始识别 code=${root.optInt("code")}")
                    status("识别中…")
                }
                "result" -> {
                    val data = root.optJSONObject("data") ?: return
                    val cn = data.optJSONObject("cn") ?: return
                    val st = cn.optJSONObject("st") ?: return
                    val rt = st.optJSONArray("rt") ?: return
                    val sb = StringBuilder()
                    for (i in 0 until rt.length()) {
                        val seg = rt.getJSONObject(i)
                        val wsArr = seg.optJSONArray("ws") ?: continue
                        for (j in 0 until wsArr.length()) {
                            val w = wsArr.getJSONObject(j)
                            val cw = w.optJSONArray("cw") ?: continue
                            if (cw.length() > 0) sb.append(cw.getJSONObject(0).optString("w", ""))
                        }
                    }
                    if (sb.isNotEmpty()) {
                        log("讯飞识别: $sb")
                        appendResult(sb.toString())
                    }
                }
                "error" -> {
                    log("讯飞错误 code=${root.optInt("code")} msg=${root.optString("msg")}")
                    status("讯飞错误: ${root.optString("msg")}")
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
    }
}
