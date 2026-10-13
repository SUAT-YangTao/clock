package com.example.clock.alarm

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingingAlarmTest {

    private fun emptyIntent() =
        Intent(ApplicationProvider.getApplicationContext(), AlarmReceiver::class.java)

    @Test
    fun `写入后能原样读出`() {
        val original = RingingAlarm(id = 5L, hour = 7, minute = 30, label = "起床", vibrate = false)
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
        val original = RingingAlarm(id = 1L, hour = 6, minute = 0, label = "", vibrate = true)
        val intent = emptyIntent()
        original.writeTo(intent)
        assertEquals("", RingingAlarm.from(intent)?.label)
    }

    @Test
    fun `写出的 Intent 与 AlarmScheduler 约定的 key 一致`() {
        val intent = emptyIntent()
        RingingAlarm(id = 3L, hour = 8, minute = 15, label = "x", vibrate = true).writeTo(intent)

        assertEquals(3L, intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L))
        assertEquals(8, intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1))
        assertEquals(15, intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1))
        assertEquals("x", intent.getStringExtra(AlarmScheduler.EXTRA_LABEL))
        assertEquals(true, intent.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, false))
    }
}
