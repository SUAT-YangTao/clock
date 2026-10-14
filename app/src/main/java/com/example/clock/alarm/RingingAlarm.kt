package com.example.clock.alarm

import android.content.Intent
import com.example.clock.domain.RepeatDays

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
    val repeatDays: RepeatDays,
) {
    fun writeTo(intent: Intent) {
        intent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, id)
        intent.putExtra(AlarmScheduler.EXTRA_HOUR, hour)
        intent.putExtra(AlarmScheduler.EXTRA_MINUTE, minute)
        intent.putExtra(AlarmScheduler.EXTRA_LABEL, label)
        intent.putExtra(AlarmScheduler.EXTRA_VIBRATE, vibrate)
        intent.putExtra(AlarmScheduler.EXTRA_REPEAT_MASK, repeatDays.mask)
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
                // 缺这个 extra 绝不能当成"非法"：app 更新前建的 PendingIntent 可能还活着，
                // 把它判成"不响"是这个 app 最坏的失败形态。默认值 = 仅一次。
                repeatDays = RepeatDays(
                    intent.getIntExtra(
                        AlarmScheduler.EXTRA_REPEAT_MASK,
                        RepeatDays.NONE_MASK,
                    ),
                ),
            )
        }

    }
}
