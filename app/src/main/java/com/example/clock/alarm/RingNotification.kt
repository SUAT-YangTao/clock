package com.example.clock.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 响铃通知。它有三个作用：
 * 1. 前台服务的载体（没有通知就不能是前台服务）
 * 2. 全屏 Activity 被系统抑制时的最后一道防线
 * 3. 提供不进入界面就能关闭/贪睡的入口
 */
object RingNotification {

    const val CHANNEL_ID = "alarm_ringing"
    const val NOTIFICATION_ID = 1001

    private const val CHANNEL_NAME = "闹钟响铃"

    private const val TAG = "RingNotification"

    /**
     * 响铃 Activity 的类名。刻意用字符串而不是类引用：alarm 层不能 import ui 层
     * （唯一的分层例外是 AlarmReceiver → RingActivity，那是启动 Activity 的
     * 显式 Intent，理由不同）。
     */
    private const val RING_ACTIVITY_CLASS = "com.example.clock.ui.ring.RingActivity"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "闹钟到点时的响铃提示"
            setShowBadge(true)
            enableVibration(false)   // 震动由 MediaPlayerAlarmPlayer 自己控制
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, ringing: RingingAlarm): Notification {
        val timeText = formatTime(ringing.hour, ringing.minute)
        val bodyText = ringing.label.ifBlank { "闹钟时间到了" }

        // Android 14 起全屏 Intent 是特殊权限，未授予时 setFullScreenIntent 会**静默**
        // 退化成普通通知 —— 留一行日志，免得"响铃没弹出来"变成一个无从下手的谜。
        // canUseFullScreenIntent() 是 API 34，minSdk 26，版本判断是必需的。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                Log.w(TAG, "全屏 Intent 权限未授予，响铃将退化为普通通知")
            }
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("$timeText 闹钟")
            .setContentText(bodyText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(ringActivityIntent(context, ringing))
            // 锁屏上直接弹出响铃界面。与 AlarmReceiver 直接拉 Activity 形成双保险，
            // RingActivity 声明为 singleTask，重复触发只会走 onNewIntent。
            .setFullScreenIntent(ringActivityIntent(context, ringing), true)
            .addAction(
                0,
                "贪睡 ${SnoozeManager.SNOOZE_MINUTES} 分钟",
                serviceAction(context, RingService.ACTION_SNOOZE, ringing),
            )
            .addAction(
                0,
                "关闭",
                serviceAction(context, RingService.ACTION_STOP, ringing),
            )
            .build()
    }

    private fun formatTime(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour, minute)

    /** 指向全屏响铃界面。用类名字符串而非类引用，保持 alarm 层不依赖 ui 层。 */
    private fun ringActivityIntent(
        context: Context,
        ringing: RingingAlarm,
    ): PendingIntent {
        val intent = Intent().apply {
            // 刻意用类名字符串而不是类引用：alarm 层不能 import ui 层。
            // 必须是显式 Intent —— 隐式 Intent 在 Android 14+ 上不匹配非导出组件，
            // 而这既是通知点击入口，也是全屏 Intent（响铃的最后一道防线）。
            setClassName(context.packageName, RING_ACTIVITY_CLASS)
            // 全屏 Intent 由系统在非 Activity 的上下文里启动，manifest 已把 RingActivity
            // 声明为 singleTask + taskAffinity=""，补 NEW_TASK 让这个 Intent 与那个声明一致。
            // 代价不对称：若本不需要，singleTask Activity 上的 NEW_TASK 不改变任何行为；
            // 若需要而没加，全屏响铃会静默退化成一条普通通知。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ringing.writeTo(this)
        }
        return PendingIntent.getActivity(
            context,
            ringing.id.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun serviceAction(
        context: Context,
        action: String,
        ringing: RingingAlarm,
    ): PendingIntent {
        val intent = Intent(context, RingService::class.java).apply {
            this.action = action
            ringing.writeTo(this)
        }
        return PendingIntent.getService(
            context,
            ringing.id.toInt() + action.hashCode().and(0xFFFF),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
