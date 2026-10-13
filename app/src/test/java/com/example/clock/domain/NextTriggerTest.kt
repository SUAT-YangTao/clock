package com.example.clock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class NextTriggerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, nano: Int = 0,
    ): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, second, nano, zone)

    /** 2026-10-12 是周一，用它作为基准日，星期几一目了然。 */
    private fun monday(hour: Int, minute: Int, second: Int = 0, nano: Int = 0) =
        at(2026, 10, 12, hour, minute, second, nano)

    private val monWedFri = RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).mask
    private val once = RepeatDays.NONE_MASK
    private val everyDay = RepeatDays.EVERY_DAY_MASK

    @Test
    fun `场景1 今天未过点则返回今天`() {
        val result = NextTrigger.next(7, 0, once, monday(6, 59))
        assertEquals(monday(7, 0), result)
    }

    @Test
    fun `场景2 一次性闹钟今天已过点则返回明天`() {
        val result = NextTrigger.next(7, 0, once, monday(7, 1))
        assertEquals(at(2026, 10, 13, 7, 0), result)
    }

    @Test
    fun `场景3 重复闹钟今天已过点则跳到下一个命中的星期`() {
        val result = NextTrigger.next(7, 0, monWedFri, monday(7, 1))
        assertEquals(at(2026, 10, 14, 7, 0), result)   // 周一 → 周三
    }

    @Test
    fun `场景4 恰好等于目标时刻时必须返回下一个周期而不是当前时刻`() {
        val result = NextTrigger.next(7, 0, once, monday(7, 0, second = 0, nano = 0))
        assertEquals(at(2026, 10, 13, 7, 0), result)
    }

    @Test
    fun `场景5 周二触发时跳到周三`() {
        val result = NextTrigger.next(7, 0, monWedFri, at(2026, 10, 13, 8, 0))
        assertEquals(at(2026, 10, 14, 7, 0), result)
    }

    @Test
    fun `场景6 每天重复时 23-59 返回明天 07-00`() {
        val result = NextTrigger.next(7, 0, everyDay, monday(23, 59))
        assertEquals(at(2026, 10, 13, 7, 0), result)
    }

    @Test
    fun `场景7 仅周日时从周日 08-00 跳到下周日`() {
        val sundayOnly = RepeatDays.of(DayOfWeek.SUNDAY).mask
        val sundayMorning = at(2026, 10, 18, 8, 0)          // 2026-10-18 是周日
        assertEquals(DayOfWeek.SUNDAY, sundayMorning.dayOfWeek)

        val result = NextTrigger.next(7, 0, sundayOnly, sundayMorning)
        assertEquals(at(2026, 10, 25, 7, 0), result)         // 正好 7 天后
    }

    @Test
    fun `场景8 一次性闹钟跨月`() {
        val jan31Night = at(2026, 1, 31, 23, 0)
        val result = NextTrigger.next(0, 30, once, jan31Night)
        assertEquals(at(2026, 2, 1, 0, 30), result)
    }

    @Test
    fun `场景9 周末重复时从周五跳到周六`() {
        val friday = at(2026, 10, 16, 12, 0)                 // 2026-10-16 是周五
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)

        val result = NextTrigger.next(7, 0, RepeatDays.WEEKENDS_MASK, friday)
        assertEquals(at(2026, 10, 17, 7, 0), result)
    }

    @Test
    fun `场景10 仅周一且当天未过点时返回今天`() {
        val mondayOnly = RepeatDays.of(DayOfWeek.MONDAY).mask
        val result = NextTrigger.next(7, 0, mondayOnly, monday(6, 0))
        assertEquals(monday(7, 0), result)
    }

    @Test
    fun `场景11 23-59 的闹钟在 23-59-30 时返回明天`() {
        val result = NextTrigger.next(23, 59, once, monday(23, 59, second = 30))
        assertEquals(at(2026, 10, 13, 23, 59), result)
    }

    @Test
    fun `场景12 00-00 的闹钟在 00-00-00-000 时返回明天`() {
        val result = NextTrigger.next(0, 0, once, at(2026, 10, 12, 0, 0, 0, 0))
        assertEquals(at(2026, 10, 13, 0, 0), result)
    }

    @Test
    fun `毫秒之差也算已过点`() {
        val result = NextTrigger.next(7, 0, once, monday(7, 0, nano = 1))
        assertEquals(at(2026, 10, 13, 7, 0), result)
    }

    @Test
    fun `结果始终严格晚于 now`() {
        val samples = listOf(
            monday(0, 0), monday(6, 59), monday(7, 0), monday(7, 1), monday(23, 59, second = 59),
        )
        for (now in samples) {
            for (mask in listOf(once, everyDay, monWedFri, RepeatDays.WEEKENDS_MASK)) {
                val result = NextTrigger.next(7, 0, mask, now)
                assert(result.isAfter(now)) { "mask=$mask, now=$now, result=$result" }
            }
        }
    }

    @Test
    fun `结果落在目标时刻上且秒与纳秒归零`() {
        val result = NextTrigger.next(7, 0, monWedFri, monday(9, 30, second = 45, nano = 123))
        assertEquals(7, result.hour)
        assertEquals(0, result.minute)
        assertEquals(0, result.second)
        assertEquals(0, result.nano)
        assertEquals(zone, result.zone)
    }

    @Test
    fun `跨年边界`() {
        val newYearEve = at(2026, 12, 31, 23, 0)
        val result = NextTrigger.next(6, 30, once, newYearEve)
        assertEquals(at(2027, 1, 1, 6, 30), result)
    }

    @Test
    fun `闰年 2 月 29 日边界`() {
        val feb28 = at(2028, 2, 28, 23, 0)                   // 2028 是闰年
        val result = NextTrigger.next(6, 0, once, feb28)
        assertEquals(at(2028, 2, 29, 6, 0), result)
    }

    @Test
    fun `非法参数抛出异常`() {
        val now = monday(12, 0)
        assertThrows(IllegalArgumentException::class.java) { NextTrigger.next(24, 0, once, now) }
        assertThrows(IllegalArgumentException::class.java) { NextTrigger.next(-1, 0, once, now) }
        assertThrows(IllegalArgumentException::class.java) { NextTrigger.next(7, 60, once, now) }
        assertThrows(IllegalArgumentException::class.java) { NextTrigger.next(7, -1, once, now) }
    }
}
