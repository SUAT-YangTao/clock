package com.example.clock.domain

import java.time.DayOfWeek

/**
 * 闹钟的重复星期设置。
 *
 * 用 7 位掩码表示：bit0 = 周一，bit1 = 周二，…，bit6 = 周日，
 * 对应 [DayOfWeek.get].value - 1。
 *
 * 掩码为 0 表示"仅响一次"——这是全项目的统一约定，见 [isOnceOnly]。
 */
@JvmInline
value class RepeatDays(val mask: Int) {

    /** 掩码为 0，即不重复，只响一次。 */
    val isOnceOnly: Boolean get() = mask == NONE_MASK

    /** 该星期是否命中本设置。 */
    fun includes(day: DayOfWeek): Boolean = (mask and bitOf(day)) != 0

    /** 把某一天加入（原本没有）或移出（原本有）。 */
    fun toggle(day: DayOfWeek): RepeatDays = RepeatDays(mask xor bitOf(day))

    /** 用于列表页展示的中文摘要。 */
    fun summary(): String = when (mask) {
        NONE_MASK -> "仅一次"
        EVERY_DAY_MASK -> "每天"
        WEEKDAYS_MASK -> "周一至周五"
        WEEKENDS_MASK -> "周末"
        else -> DayOfWeek.entries
            .filter { includes(it) }
            .joinToString("、") { shortName(it) }
    }

    companion object {
        const val NONE_MASK = 0x00
        const val EVERY_DAY_MASK = 0x7F      // 0b111_1111
        const val WEEKDAYS_MASK = 0x1F       // 0b001_1111 周一~周五
        const val WEEKENDS_MASK = 0x60       // 0b110_0000 周六、周日

        val NONE = RepeatDays(NONE_MASK)
        val EVERY_DAY = RepeatDays(EVERY_DAY_MASK)
        val WEEKDAYS = RepeatDays(WEEKDAYS_MASK)
        val WEEKENDS = RepeatDays(WEEKENDS_MASK)

        /** 某一天对应的单个 bit。 */
        fun bitOf(day: DayOfWeek): Int = 1 shl (day.value - 1)

        /** 由若干天构造。 */
        fun of(vararg days: DayOfWeek): RepeatDays =
            RepeatDays(days.fold(0) { acc, day -> acc or bitOf(day) })
    }
}

private fun shortName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}
