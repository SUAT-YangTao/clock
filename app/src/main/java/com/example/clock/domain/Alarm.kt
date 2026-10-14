package com.example.clock.domain

/**
 * 一个闹钟设置。
 *
 * 纯 Kotlin 数据类，不依赖任何 Android 或 Room 类型。
 *
 * [id] 为 0 表示尚未写入数据库的新闹钟。
 */
data class Alarm(
    val id: Long = 0L,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val repeatDays: RepeatDays = RepeatDays.NONE,
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
)
