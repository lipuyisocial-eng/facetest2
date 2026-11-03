package com.xingyihui.interviewassistant.diag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 前台服务：实时转写推流期间保活。
 *
 * 背景（2026-11-04 真机实测）：Find N3 锁屏/退后台后，ColorOS 冻结后台应用（进程 CPU≈0%），
 * 推流线程无 CPU 时间片 → 40ms 帧节奏完全错乱 → 讯飞 RTASR 静默拒识（零 result）。
 * 以「前台服务 + 通知」提升进程优先级，规避冻结。
 */
class DiagService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("rtasr", "实时转写", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = Notification.Builder(this, "rtasr")
            .setContentTitle("面试博弈助手")
            .setContentText("实时转写运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1)
        super.onDestroy()
    }
}
