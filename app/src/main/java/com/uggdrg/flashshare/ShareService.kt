package com.uggdrg.flashshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.net.ServerSocket

class ShareService : Service() {

    private var server: FileServer? = null
    private var tunnel: Tunnel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var expiresAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server != null) return START_NOT_STICKY

        val files = ShareState.files
        if (files.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val duration = intent?.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS) ?: DEFAULT_DURATION_MS
        val password = intent?.getStringExtra(EXTRA_PASSWORD)?.takeIf { it.isNotEmpty() }
        expiresAt = System.currentTimeMillis() + duration
        ShareState.resetHits()

        createChannel()
        val first = buildNotification("正在启动…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, first)
        }
        ShareState.status.postValue(ShareStatus.Starting)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlashShare:share").apply {
            acquire(duration + 60_000)
        }

        try {
            val port = ServerSocket(0).use { it.localPort }
            val s = FileServer(port, applicationContext, files, password)
            s.start(30_000, false)
            server = s

            tunnel = Tunnel(
                localPort = port,
                onUrl = { url ->
                    ShareState.status.postValue(ShareStatus.Live(url, expiresAt))
                    notify(buildNotification("分享中：$url"))
                },
                onStatus = { msg -> ShareState.status.postValue(ShareStatus.Waiting(msg)) },
                onFatal = { msg ->
                    ShareState.status.postValue(ShareStatus.Error(msg))
                    handler.post { stopSelf() }
                }
            ).also { it.start() }
        } catch (e: Exception) {
            ShareState.status.postValue(ShareStatus.Error("启动失败：${e.message}"))
            stopSelf()
            return START_NOT_STICKY
        }

        handler.postDelayed({ stopSelf() }, duration)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tunnel?.stop()
        tunnel = null
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (ShareState.status.value !is ShareStatus.Error) {
            ShareState.status.postValue(ShareStatus.Idle)
        }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "文件分享", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("闪传")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "停止分享", stop)
            .build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    companion object {
        const val ACTION_STOP = "com.uggdrg.flashshare.STOP"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_PASSWORD = "password"
        const val DEFAULT_DURATION_MS = 6 * 60 * 60 * 1000L
        private const val CHANNEL_ID = "share"
        private const val NOTIF_ID = 1
    }
}
