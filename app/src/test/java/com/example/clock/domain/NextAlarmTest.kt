package com.example.clock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class NextAlarmTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone)

    private fun alarm(
        id: Long,
        hour: Int,
        minute: Int,
        enabled: Boolean = true,
        mask: Int = RepeatDays.NONE_MASK,
    ) = Alarm(id = id, hour = hour, minute = minute, repeatDays = RepeatDays(mask), enabled = enabled)

    @Test
    fun `无闹钟时返回 null`() {
        assertNull(NextTrigger.nextOf(emptyList(), at(10, 12, 8, 0)))
    }

    @Test
    fun `全部禁用时返回 null`() {
        val alarms = listOf(alarm(1, 7, 0, enabled = false), alarm(2, 9, 0, enabled = false))
        assertNull(NextTrigger.nextOf(alarms, at(10, 12, 8, 0)))
    }

    @Test
    fun `返回时间最早的那个闹钟`() {
        val alarms = listOf(alarm(1, 9, 0), alarm(2, 7, 0), alarm(3, 8, 0))
        val result = NextTrigger.nextOf(alarms, at(10, 12, 6, 0))
        assertEquals(2L, result?.alarm?.id)
        assertEquals(at(10, 12, 7, 0), result?.at)
    }

    @Test
    fun `禁用中的闹钟不参与比较`() {
        val alarms = listOf(alarm(1, 7, 0, enabled = false), alarm(2, 9, 0))
        val result = NextTrigger.nextOf(alarms, at(10, 12, 6, 0))
        assertEquals(2L, result?.alarm?.id)
    }

    @Test
    fun `按绝对时刻比较而不是按小时数`() {
        val today = alarm(1, 23, 0, mask = RepeatDays.WEEKDAYS_MASK)
        val tomorrow = alarm(2, 1, 0, mask = RepeatDays.EVERY_DAY_MASK)
        // 周一 10:00
        val now = ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, zone)
        assertEquals(DayOfWeek.MONDAY, now.dayOfWeek)

        val result = NextTrigger.nextOf(listOf(today, tomorrow), now)
        // today：周一 23:00（今天稍晚）；tomorrow：周二 01:00。今天 23:00 更早。
        assertEquals(1L, result?.alarm?.id)
        assertEquals(ZonedDateTime.of(2026, 10, 12, 23, 0, 0, 0, zone), result?.at)
    }

    @Test
    fun `剩余时间文案 超过一小时`() {
        assertEquals("7 小时 12 分钟后", NextTrigger.formatRemaining(at(10, 12, 0, 0), at(10, 12, 7, 12)))
    }

    @Test
    fun `剩余时间文案 恰好整小时`() {
        assertEquals("3 小时后", NextTrigger.formatRemaining(at(10, 12, 0, 0), at(10, 12, 3, 0)))
    }

    @Test
    fun `剩余时间文案 不足一小时`() {
        assertEquals("45 分钟后", NextTrigger.formatRemaining(at(10, 12, 0, 0), at(10, 12, 0, 45)))
    }

    @Test
    fun `剩余时间文案 不足一分钟`() {
        val from = ZonedDateTime.of(2026, 10, 12, 7, 0, 30, 0, zone)
        assertEquals("剩余零秒", "不到 1 分钟", NextTrigger.formatRemaining(from, from))
        assertEquals("剩余 30 秒", "不到 1 分钟", NextTrigger.formatRemaining(from, from.plusSeconds(30)))
        assertEquals("剩余 59 秒", "不到 1 分钟", NextTrigger.formatRemaining(from, from.plusSeconds(59)))
    }

    @Test
    fun `剩余时间文案 恰好一分钟`() {
        val from = ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, zone)
        assertEquals("1 分钟后", NextTrigger.formatRemaining(from, from.plusMinutes(1)))
    }

    @Test
    fun `剩余时间文案 跨天`() {
        assertEquals("1 天 2 小时后", NextTrigger.formatRemaining(at(10, 12, 22, 0), at(10, 14, 0, 0)))
    }
}
