package com.example.clock.ui.list

import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class AlarmListSummaryTest {

    // 固定为周一 22:00（+08:00），让"下一个"的期望值完全确定。
    // 注意 2026-10-12 才是周一：brief 原稿写的 2026-10-13 实际是周二，
    // 会让下面第三个用例期望的"周二 07:00"落空（见 task-15-report.md）。
    private val now = ZonedDateTime.parse("2026-10-12T22:00:00+08:00")

    private fun summaryOf(vararg alarms: Alarm) = buildNextSummary(alarms.toList(), now)

    @Test
    fun `列表为空时没有摘要`() {
        assertNull(summaryOf())
    }

    @Test
    fun `有闹钟但一个都没启用时提示没有启用的闹钟`() {
        val disabled = Alarm(
            id = 1L, hour = 7, minute = 0,
            repeatDays = RepeatDays.EVERY_DAY, enabled = false,
        )
        assertEquals("没有启用的闹钟", summaryOf(disabled))
    }

    @Test
    fun `有启用的闹钟时给出下一个的时刻与剩余时间`() {
        // now 是周一 22:00，工作日 7:00 的下一次落在周二。
        val enabled = Alarm(id = 1L, hour = 7, minute = 0, repeatDays = RepeatDays.WEEKDAYS)
        assertTrue(summaryOf(enabled)!!.startsWith("下一个闹钟：周二 07:00（"))
    }

    @Test
    fun `未启用的闹钟不参与计算`() {
        val disabled = Alarm(
            id = 1L, hour = 7, minute = 0,
            repeatDays = RepeatDays.EVERY_DAY, enabled = false,
        )
        val enabled = Alarm(id = 2L, hour = 9, minute = 30, repeatDays = RepeatDays.EVERY_DAY)
        assertTrue(summaryOf(disabled, enabled)!!.contains("09:30"))
    }
}
