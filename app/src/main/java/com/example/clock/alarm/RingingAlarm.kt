package com.example.clock.alarm

import android.content.Intent

/**
 * 一次响铃所需的全部展示数据。
 *
 * 这些值随 PendingIntent 一起传递，使 AlarmReceiver 不必查数据库就能拉起界面。
 */
data class RingingAlarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val vibrate: Boolean,
) {
    fun writeTo(intent: Intent) {
        intent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, id)
        intent.putExtra(AlarmScheduler.EXTRA_HOUR, hour)
        intent.putExtra(AlarmScheduler.EXTRA_MINUTE, minute)
        intent.putExtra(AlarmScheduler.EXTRA_LABEL, label)
        intent.putExtra(AlarmScheduler.EXTRA_VIBRATE, vibrate)
    }

    companion object {
        /** 从 Intent 还原。数据缺失或非法时返回 null，调用方应直接放弃这次响铃。 */
        fun from(intent: Intent): RingingAlarm? {
            val id = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
            if (id <= 0L) return null

            if (!intent.hasExtra(AlarmScheduler.EXTRA_HOUR)) return null
            if (!intent.hasExtra(AlarmScheduler.EXTRA_MINUTE)) return null
            val hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1)
            val minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1)
            if (hour !in 0..23 || minute !in 0..59) return null

            return RingingAlarm(
                id = id,
                hour = hour,
                minute = minute,
                label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty(),
                vibrate = intent.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, true),
            )
        }

    }
}
