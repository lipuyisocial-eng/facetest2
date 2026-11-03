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
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private fun startRecording(tag: String, saveToFile: Boolean): Boolean {
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
                .setChannelMask(CHANNEL)
                .setEncoding(ENCODING)
                .build())
            .setBufferSizeInBytes(data.size.coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            log("Test A AudioTrack 初始化失败 state=${track.state}")
            track.release()
            return
        }
        track.write(data, 0, data.size)
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
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
