package com.example.clock.alarm

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.clock.domain.RepeatDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingingAlarmTest {

    private fun emptyIntent() =
        Intent(ApplicationProvider.getApplicationContext(), AlarmReceiver::class.java)

    @Test
    fun `写入后能原样读出`() {
        val original = RingingAlarm(
            id = 5L,
            hour = 7,
            minute = 30,
            label = "起床",
            vibrate = false,
            repeatDays = RepeatDays.WEEKDAYS,
        )
        val intent = emptyIntent()

        original.writeTo(intent)
        val restored = RingingAlarm.from(intent)

        assertEquals(original, restored)
    }

    @Test
    fun `缺少 alarmId 时返回 null`() {
        assertNull(RingingAlarm.from(emptyIntent()))
    }

    @Test
    fun `alarmId 非法时返回 null`() {
        val intent = emptyIntent().putExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        assertNull(RingingAlarm.from(intent))
    }

    @Test
    fun `缺少 hour 时返回 null`() {
        val intent = emptyIntent().putExtra(AlarmScheduler.EXTRA_ALARM_ID, 5L)
        assertNull(RingingAlarm.from(intent))
    }

    @Test
    fun `小时越界时返回 null`() {
        val intent = emptyIntent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 5L)
            putExtra(AlarmScheduler.EXTRA_HOUR, 24)
            putExtra(AlarmScheduler.EXTRA_MINUTE, 0)
        }
        assertNull(RingingAlarm.from(intent))
    }

    @Test
    fun `空标签也能往返`() {
        val original = RingingAlarm(
            id = 1L,
            hour = 6,
            minute = 0,
            label = "",
            vibrate = true,
            repeatDays = RepeatDays.NONE,
        )
        val intent = emptyIntent()
        original.writeTo(intent)
        assertEquals("", RingingAlarm.from(intent)?.label)
    }

    @Test
    fun `写出的 Intent 与 AlarmScheduler 约定的 key 一致`() {
        val intent = emptyIntent()
        RingingAlarm(
            id = 3L,
            hour = 8,
            minute = 15,
            label = "x",
            vibrate = true,
            repeatDays = RepeatDays.EVERY_DAY,
        ).writeTo(intent)

        assertEquals(3L, intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L))
        assertEquals(8, intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1))
        assertEquals(15, intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1))
        assertEquals("x", intent.getStringExtra(AlarmScheduler.EXTRA_LABEL))
        assertEquals(true, intent.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, false))
        assertEquals(
            RepeatDays.EVERY_DAY_MASK,
            intent.getIntExtra(AlarmScheduler.EXTRA_REPEAT_MASK, RepeatDays.NONE_MASK),
        )
    }

    /**
     * 向后兼容：app 更新前建立的 PendingIntent 里没有 EXTRA_REPEAT_MASK。
     * 缺这个 extra **绝不能**被当成"数据非法"，否则一个跨版本存活的注册
     * 会变成"不响"—— 这是本 app 最坏的失败形态。默认值 = 仅一次。
     */
    @Test
    fun `缺少 repeat_mask 时仍能还原为仅一次`() {
        val intent = emptyIntent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 5L)
            putExtra(AlarmScheduler.EXTRA_HOUR, 7)
            putExtra(AlarmScheduler.EXTRA_MINUTE, 0)
        }

        val restored = RingingAlarm.from(intent)

        assertNotNull("缺 repeat extra 不能被判成非法", restored)
        assertTrue("默认必须是仅一次", restored!!.repeatDays.isOnceOnly)
    }
}
