package com.example.clock.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays

/**
 * 闹钟的持久化形式。
 *
 * 注意这里**没有**"下次触发时间"字段——那是刻意的设计：任何持久化的触发时间
 * 都可能与实际状态不同步（用户改了系统时间、时区变了），一旦不同步就会出现
 * "闹钟静默不响"这种最难排查的故障。下次触发时间永远由 AlarmScheduler 现算。
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hour: Int,
    val minute: Int,
    val label: String,
    val repeatMask: Int,
    val enabled: Boolean,
    val vibrate: Boolean,
)

fun AlarmEntity.toDomain(): Alarm = Alarm(
    id = id,
    hour = hour,
    minute = minute,
    label = label,
    repeatDays = RepeatDays(repeatMask),
    enabled = enabled,
    vibrate = vibrate,
)

fun Alarm.toEntity(): AlarmEntity = AlarmEntity(
    id = id,
    hour = hour,
    minute = minute,
    label = label,
    repeatMask = repeatDays.mask,
    enabled = enabled,
    vibrate = vibrate,
)
