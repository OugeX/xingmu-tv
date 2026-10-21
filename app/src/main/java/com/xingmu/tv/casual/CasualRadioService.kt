package com.xingmu.tv.casual

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.xingmu.tv.R
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.data.casual.CasualGraph

/**
 * 电台前台服务（API 23 兼容）：切到桌面/其它 App 时保持出声，通知栏提供上一台/暂停/下一台/停止。
 *
 * 注意：
 * - Manifest 已注册 `com.xingmu.tv.casual.CasualRadioService` + FOREGROUND_SERVICE(_MEDIA_PLAYBACK)；
 * - API 26+ 才需要 NotificationChannel；API 23 直接 Notification.Builder；
 * - 不使用 MediaSession 的 API 26+ 专属能力（仅普通 PendingIntent action）。
 */
class CasualRadioService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = CasualGraph.radioSessionOrNull()
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_NEXT -> session.next()
            ACTION_PREV -> session.previous()
            ACTION_TOGGLE -> session.togglePause()
            ACTION_STOP -> {
                session.setActive(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification(session.state.value.station?.name))
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "电台播放", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                },
            )
        }
    }

    private fun buildNotification(stationName: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.xingmu.tv.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(stationName ?: "随便听听")
            .setContentText("电台播放中 · 切到其它页面继续")
            .setContentIntent(open)
            .setOngoing(true)
        addActionCompat(builder, ACTION_PREV, "上一台", 1)
        addActionCompat(builder, ACTION_TOGGLE, "播放/暂停", 2)
        addActionCompat(builder, ACTION_NEXT, "下一台", 3)
        addActionCompat(builder, ACTION_STOP, "停止", 4)
        return builder.build()
    }

    private fun addActionCompat(
        builder: Notification.Builder,
        action: String,
        title: String,
        code: Int,
    ) {
        val intent = Intent(this, CasualRadioService::class.java).setAction(action)
        val pending = PendingIntent.getService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_logo),
                    title,
                    pending,
                ).build(),
            )
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(R.drawable.ic_logo, title, pending)
        }
    }

    companion object {
        private const val TAG = "CasualRadioService"
        private const val CHANNEL_ID = "casual_radio"
        private const val NOTIFICATION_ID = 3401
        const val ACTION_NEXT = "com.xingmu.tv.casual.NEXT"
        const val ACTION_PREV = "com.xingmu.tv.casual.PREV"
        const val ACTION_TOGGLE = "com.xingmu.tv.casual.TOGGLE"
        const val ACTION_STOP = "com.xingmu.tv.casual.STOP"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, CasualRadioService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { TvLog.w(TAG, "启动前台服务失败：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CasualRadioService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
