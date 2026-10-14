package com.example.clock.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 响铃前台服务。
 *
 * 它**不是**铃声的持有者（那是 RingAudioController），职责只有两个：
 * 1. 用常驻通知把进程提升为前台，降低被系统回收的概率
 * 2. 提供通知栏上的关闭/贪睡入口
 *
 * 正因为铃声音频不归它独占，前台服务启动失败时（见 spec 5.6）铃声仍能由
 * RingActivity 播放，不会出现"服务没了就彻底不响"。
 */
class RingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ringing = intent?.let { RingingAlarm.from(it) }
        if (ringing == null) {
            // 数据不完整，无法响铃也无法展示，直接退出。
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                RingAudioController.get(this).stop()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SNOOZE -> {
                SnoozeManager(this).snooze(ringing)
                RingAudioController.get(this).stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // 默认动作：进入响铃状态。
        // Android 12+ 要求 startForegroundService 后必须尽快调用 startForeground，
        // 因此放在最前面，晚于它做任何事都可能触发 ANR。
        try {
            RingNotification.ensureChannel(this)
            startForeground(RingNotification.NOTIFICATION_ID, RingNotification.build(this, ringing))
        } catch (e: Exception) {
            // 例如用户关闭了通知权限导致通知无法展示。此时服务退化为普通后台服务，
            // 铃声响不响交给 RingActivity，不让异常中断流程。
            Log.w(TAG, "进入前台失败，降级为普通服务", e)
        }

        // 播放失败不要让服务崩掉。RingAudioController.start() 失败时会把状态回滚成
        // "未在响铃"再原样抛出，所以 RingActivity.onStart() 里那次 start() 会重新尝试播放 ——
        // 这正是 spec 5.6 想要的降级路径。因此这里只记录，不重抛。
        try {
            RingAudioController.get(this).start(ringing.vibrate)
        } catch (t: Throwable) {
            Log.w(TAG, "开始播放铃声失败，交给响铃界面兜底", t)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RingAudioController.get(this).stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RingService"

        const val ACTION_STOP = "com.example.clock.action.STOP_RINGING"
        const val ACTION_SNOOZE = "com.example.clock.action.SNOOZE"

        fun intent(context: Context, ringing: RingingAlarm): Intent =
            Intent(context, RingService::class.java).apply { ringing.writeTo(this) }

        /**
         * 启动前台服务。不在此处吞异常 —— 调用方（AlarmReceiver）需要知道是否
         * 失败，以便走降级路径。
         */
        fun start(context: Context, ringing: RingingAlarm) {
            ContextCompat.startForegroundService(context, intent(context, ringing))
        }
    }
}
