package com.xingyihui.interviewassistant.diag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * 前台服务：锁屏 / 切后台时保持收音转写与播报。
 *
 * 背景：真实面试手机放裤兜（锁屏）使用，ColorOS 会冻结后台进程。
 * 按 Android 13+ 要求以 FOREGROUND_SERVICE_TYPE_MICROPHONE 启动前台服务，
 * 并持有 PARTIAL_WAKE_LOCK，保证录音线程/WebSocket 在灭屏后仍有 CPU 时间片。
 */
class DiagService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("rtasr", "实时转写", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "rtasr")
            .setContentTitle("面试博弈助手 · 收音中")
            .setContentText("锁屏/后台继续工作，耳机听提示；点开回到界面")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }

        // 灭屏后仍保证录音线程与 WebSocket 有 CPU 时间片
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InterviewApp::rtasr").apply {
                setReferenceCounted(false)
                acquire(35 * 60 * 1000L)
            }
            Log.i("InterviewApp", "前台服务已启动（microphone 类型）+ 持有唤醒锁")
        } catch (e: Exception) {
            Log.i("InterviewApp", "唤醒锁获取失败: ${e.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1)
        super.onDestroy()
    }
}
