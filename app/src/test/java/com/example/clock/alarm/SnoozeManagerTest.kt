package com.example.clock.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class SnoozeManagerTest {

    private lateinit var context: Context
    private lateinit var snoozeManager: SnoozeManager
    private lateinit var alarmManager: AlarmManager

    private val ringing = RingingAlarm(id = 3L, hour = 7, minute = 0, label = "起床", vibrate = false)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        snoozeManager = SnoozeManager(context)
        alarmManager = context.getSystemService(AlarmManager::class.java)
        // 静态方法，不能经由 shadowOf(...) 的实例调用。
        ShadowAlarmManager.setAutoSchedule(false)
    }

    @Test
    fun `贪睡注册的是十分钟后`() {
        val now = 1_000_000_000_000L
        snoozeManager.snooze(ringing, nowMillis = now)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull(scheduled)
        assertEquals(now + 10 * 60_000L, scheduled!!.triggerAtTime)
    }

    @Test
    fun `贪睡时长常量是十分钟`() {
        assertEquals(10L, SnoozeManager.SNOOZE_MINUTES)
    }

    @Test
    fun `贪睡注册用的 requestCode 与普通闹钟不冲突`() {
        val now = 1_000_000_000_000L
        snoozeManager.snooze(ringing, nowMillis = now)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull(scheduled)
        // 普通闹钟的 requestCode 是 alarm.id 本身（3），贪睡必须是另一个值。
        assertEquals(
            SnoozeManager.SNOOZE_REQUEST_OFFSET + ringing.id.toInt(),
            shadowOf(scheduled!!.operation).requestCode,
        )
    }

    @Test
    fun `取消贪睡后没有待触发的闹钟`() {
        val now = 1_000_000_000_000L
        snoozeManager.snooze(ringing, nowMillis = now)
        snoozeManager.cancel(ringing.id)

        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `取消不存在的贪睡是安全的`() {
        snoozeManager.cancel(999L)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `重复贪睡会重置计时`() {
        val first = 1_000_000_000_000L
        val second = first + 60_000L
        snoozeManager.snooze(ringing, nowMillis = first)
        snoozeManager.snooze(ringing, nowMillis = second)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertEquals(second + 10 * 60_000L, scheduled!!.triggerAtTime)
    }
}
