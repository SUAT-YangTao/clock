package com.example.clock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 贪睡。
 *
 * 刻意**不写数据库、不修改闹钟记录** —— 只是向 AlarmManager 注册一个十分钟后的
 * 一次性触发。响铃结束这个注册自然失效，用户的闹钟设置完全不受影响。
 */
class SnoozeManager(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    /** 注册 [SNOOZE_MINUTES] 分钟后的再次响铃。重复调用会重置计时。 */
    fun snooze(ringing: RingingAlarm, nowMillis: Long = System.currentTimeMillis()) {
        val triggerAt = nowMillis + SNOOZE_MINUTES * 60_000L

        // FLAG_UPDATE_CURRENT 下 getBroadcast 必然返回非 null，这里的 ?: return
        // 只是为了让可空类型收敛，不是真实的失败分支。
        val pi = pendingIntent(ringing) ?: return

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, showIntent()),
            pi,
        )
    }

    /** 取消某个闹钟对应的贪睡。不存在时静默返回。 */
    fun cancel(alarmId: Long) {
        val pi = pendingIntent(alarmId, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun showIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun pendingIntent(ringing: RingingAlarm): PendingIntent? =
        pendingIntentInternal(ringing.id, ringing, PendingIntent.FLAG_UPDATE_CURRENT)

    private fun pendingIntent(alarmId: Long, extraFlags: Int): PendingIntent? =
        pendingIntentInternal(alarmId, null, extraFlags)

    private fun pendingIntentInternal(
        alarmId: Long,
        ringing: RingingAlarm?,
        extraFlags: Int,
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_FIRE_ALARM
            data = Uri.parse("clock://snooze/$alarmId")
            putExtra(AlarmScheduler.EXTRA_IS_SNOOZE, true)
            ringing?.writeTo(this) ?: putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_OFFSET + alarmId.toInt(),
            intent,
            extraFlags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val SNOOZE_MINUTES = 10L

        /**
         * 贪睡的 requestCode 偏移量。
         * 与普通闹钟的 requestCode（alarm.id）拉开距离，避免两者互相覆盖。
         */
        const val SNOOZE_REQUEST_OFFSET = 100_000

        private const val SHOW_REQUEST_CODE = 99_998
    }
}
