package com.example.clock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class RepeatDaysTest {

    @Test
    fun `bit0 是周一 bit6 是周日`() {
        assertEquals(0b0000001, RepeatDays.bitOf(DayOfWeek.MONDAY))
        assertEquals(0b0000010, RepeatDays.bitOf(DayOfWeek.TUESDAY))
        assertEquals(0b0000100, RepeatDays.bitOf(DayOfWeek.WEDNESDAY))
        assertEquals(0b0001000, RepeatDays.bitOf(DayOfWeek.THURSDAY))
        assertEquals(0b0010000, RepeatDays.bitOf(DayOfWeek.FRIDAY))
        assertEquals(0b0100000, RepeatDays.bitOf(DayOfWeek.SATURDAY))
        assertEquals(0b1000000, RepeatDays.bitOf(DayOfWeek.SUNDAY))
    }

    @Test
    fun `掩码 0 表示仅一次`() {
        assertTrue(RepeatDays.NONE.isOnceOnly)
        assertTrue(RepeatDays(0).isOnceOnly)
        assertFalse(RepeatDays.WEEKDAYS.isOnceOnly)
    }

    @Test
    fun `预设常量的掩码值正确`() {
        assertEquals(0x00, RepeatDays.NONE_MASK)
        assertEquals(0x7F, RepeatDays.EVERY_DAY_MASK)
        assertEquals(0x1F, RepeatDays.WEEKDAYS_MASK)
        assertEquals(0x60, RepeatDays.WEEKENDS_MASK)
    }

    @Test
    fun `includes 只对命中的星期为真`() {
        val monWedFri = RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        assertTrue(monWedFri.includes(DayOfWeek.MONDAY))
        assertFalse(monWedFri.includes(DayOfWeek.TUESDAY))
        assertTrue(monWedFri.includes(DayOfWeek.WEDNESDAY))
        assertFalse(monWedFri.includes(DayOfWeek.THURSDAY))
        assertTrue(monWedFri.includes(DayOfWeek.FRIDAY))
        assertFalse(monWedFri.includes(DayOfWeek.SATURDAY))
        assertFalse(monWedFri.includes(DayOfWeek.SUNDAY))
    }

    @Test
    fun `WEEKDAYS 含且仅含周一至周五`() {
        val inWeek = DayOfWeek.entries.filter { RepeatDays.WEEKDAYS.includes(it) }
        assertEquals(
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
            inWeek,
        )
    }

    @Test
    fun `WEEKENDS 含且仅含周六周日`() {
        val inWeekend = DayOfWeek.entries.filter { RepeatDays.WEEKENDS.includes(it) }
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), inWeekend)
    }

    @Test
    fun `EVERY_DAY 含全部七天`() {
        assertEquals(7, DayOfWeek.entries.count { RepeatDays.EVERY_DAY.includes(it) })
    }

    @Test
    fun `toggle 加入或移出某天`() {
        val monday = RepeatDays.of(DayOfWeek.MONDAY)
        assertEquals(monday.toggle(DayOfWeek.TUESDAY), RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY))
        assertEquals(RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY).toggle(DayOfWeek.TUESDAY), monday)
        // toggle 两次回到原状
        assertEquals(monday, monday.toggle(DayOfWeek.SUNDAY).toggle(DayOfWeek.SUNDAY))
    }

    @Test
    fun `summary 覆盖全部特例分支`() {
        assertEquals("仅一次", RepeatDays.NONE.summary())
        assertEquals("每天", RepeatDays.EVERY_DAY.summary())
        assertEquals("周一至周五", RepeatDays.WEEKDAYS.summary())
        assertEquals("周末", RepeatDays.WEEKENDS.summary())
    }

    @Test
    fun `summary 的默认分支按星期顺序拼接`() {
        assertEquals(
            "周一、周三、周五",
            RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).summary(),
        )
        // 顺序必须由 DayOfWeek 的自然顺序决定，与传入顺序无关
        assertEquals(
            "周一、周三、周五",
            RepeatDays.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY).summary(),
        )
    }
}
