package com.example.clock.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.clock.AppGraph
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class AlarmReceiverTest {

    private lateinit var context: Context
    private lateinit var repository: AlarmRepository

    private fun fireIntent(ringing: RingingAlarm, isSnooze: Boolean = false): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_FIRE_ALARM
            ringing.writeTo(this)
            putExtra(AlarmScheduler.EXTRA_IS_SNOOZE, isSnooze)
        }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // AppGraph 与 AlarmDatabase 都是进程级单例，Robolectric 不会替我们重置。
        AppGraph.resetForTesting()
        repository = AppGraph.repository(context)
        // 静态方法，不能经由 shadowOf(...) 的实例调用。
        ShadowAlarmManager.setAutoSchedule(false)
    }

    /** 建一个闹钟并返回它的 id。 */
    private fun seedAlarm(mask: Int = RepeatDays.NONE_MASK): Long = runBlocking {
        repository.save(Alarm(hour = 7, minute = 0, label = "起床", repeatDays = RepeatDays(mask)))
    }

    @Test
    fun `一次性闹钟响铃后自动关闭`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        val after = runBlocking { repository.getById(id) }
        assertNotNull(after)
        assertFalse("一次性闹钟响完必须自己关掉", after!!.enabled)
    }

    @Test
    fun `重复闹钟响铃后保持启用`() {
        val id = seedAlarm(RepeatDays.WEEKDAYS_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        assertTrue("重复闹钟不应被关闭", runBlocking { repository.getById(id) }!!.enabled)
    }

    @Test
    fun `贪睡触发的响铃不再关闭任何闹钟`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true), isSnooze = true))

        assertTrue(
            "贪睡响铃不应改动闹钟",
            runBlocking { repository.getById(id) }!!.enabled,
        )
    }

    @Test
    fun `非法 Intent 时不做任何事也不崩溃`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)
        val bad = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_FIRE_ALARM
        }

        AlarmReceiver().onReceive(context, bad)

        assertTrue(runBlocking { repository.getById(id) }!!.enabled)
    }

    @Test
    fun `action 不匹配时直接返回`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)
        val wrong = fireIntent(RingingAlarm(id, 7, 0, "x", true)).apply {
            action = "com.example.clock.SOMETHING_ELSE"
        }

        AlarmReceiver().onReceive(context, wrong)

        assertTrue(runBlocking { repository.getById(id) }!!.enabled)
    }

    @Test
    fun `响铃后会重新注册重复闹钟的下一次`() {
        val id = seedAlarm(RepeatDays.EVERY_DAY_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        assertNotNull(
            "响铃后应当仍有一个待触发的闹钟",
            shadowOf(alarmManager).peekNextScheduledAlarm(),
        )
    }

    @Test
    fun `一次性闹钟响铃后不再有待触发的闹钟`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        assertTrue(
            "一次性闹钟关闭后不应还留着注册",
            shadowOf(alarmManager).peekNextScheduledAlarm() == null,
        )
    }

    @Test
    fun `前台服务启动失败时仍然完成关闭一次性闹钟`() {
        // Review Focus 第 5 条：降级路径不能中断后续的数据库善后。
        val id = seedAlarm(RepeatDays.NONE_MASK)

        val receiver = AlarmReceiver().apply {
            serviceStarter = { _, _ -> error("模拟前台服务启动被系统拒绝") }
        }
        receiver.onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        assertFalse(
            "前台服务失败不应中断关闭一次性闹钟",
            runBlocking { repository.getById(id) }!!.enabled,
        )
    }
}
