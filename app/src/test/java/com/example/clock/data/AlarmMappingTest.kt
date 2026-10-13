package com.example.clock.data

import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek

class AlarmMappingTest {

    @Test
    fun `Alarm 转 Entity 再转回完全一致`() {
        val original = Alarm(
            id = 42L,
            hour = 7,
            minute = 30,
            label = "起床",
            repeatDays = RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            enabled = false,
            vibrate = false,
        )
        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `掩码被原样搬运`() {
        val alarm = Alarm(hour = 6, minute = 0, repeatDays = RepeatDays.WEEKENDS)
        assertEquals(RepeatDays.WEEKENDS_MASK, alarm.toEntity().repeatMask)
        assertEquals(RepeatDays.WEEKENDS_MASK, alarm.toEntity().toDomain().repeatDays.mask)
    }

    @Test
    fun `新闹钟 id 为 0 时保持为 0`() {
        assertEquals(0L, Alarm(hour = 6, minute = 0).toEntity().id)
    }

    @Test
    fun `仅一次的掩码 0 也能往返`() {
        val alarm = Alarm(hour = 6, minute = 0, repeatDays = RepeatDays.NONE)
        val roundTripped = alarm.toEntity().toDomain()
        assertEquals(0, roundTripped.repeatDays.mask)
        assertEquals(true, roundTripped.repeatDays.isOnceOnly)
    }

    @Test
    fun `Entity 转 Domain 字段一一对应`() {
        val entity = AlarmEntity(
            id = 7L, hour = 21, minute = 5, label = "吃药",
            repeatMask = RepeatDays.EVERY_DAY_MASK, enabled = true, vibrate = false,
        )
        val domain = entity.toDomain()
        assertEquals(7L, domain.id)
        assertEquals(21, domain.hour)
        assertEquals(5, domain.minute)
        assertEquals("吃药", domain.label)
        assertEquals(RepeatDays.EVERY_DAY, domain.repeatDays)
        assertEquals(true, domain.enabled)
        assertEquals(false, domain.vibrate)
    }
}
