package com.example.clock.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.clock.data.AlarmDatabase
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.NextTrigger
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var alarmManager: AlarmManager
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-12 是周一 06:00，所有测试的基准 now。 */
    private val mondayMorning = ZonedDateTime.of(2026, 10, 12, 6, 0, 0, 0, zone)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        // Robolectric 4.14.1 里 setAutoSchedule 是静态方法，只能通过类名调用。
        ShadowAlarmManager.setAutoSchedule(false)

        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // 按 AppGraph 的方式接线：删除时先把 id 交给调度器取消，再整体重算。
        // 这样测试覆盖的是真实的删除路径，而不是一个人造替身。
        repository = AlarmRepository(db.alarmDao()) { removedId ->
            if (removedId != null) scheduler.cancel(removedId)
            scheduler.scheduleAll()
        }
        scheduler = AlarmScheduler(context, repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `注册的触发时刻与 NextTrigger 计算一致`() = runTest {
        repository.save(Alarm(hour = 7, minute = 0))

        scheduler.scheduleAll(mondayMorning)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull("应当注册了一个闹钟", scheduled)
        val expected = NextTrigger.next(7, 0, RepeatDays.NONE_MASK, mondayMorning)
        assertEquals(expected.toInstant().toEpochMilli(), scheduled!!.triggerAtTime)
    }

    @Test
    fun `已禁用的闹钟不被注册`() = runTest {
        repository.save(Alarm(hour = 7, minute = 0, enabled = false))

        scheduler.scheduleAll(mondayMorning)

        assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `多个闹钟各自注册，最近的那个排在最前`() = runTest {
        // setAlarmClock 注册的多个闹钟在系统中并存，状态栏显示最早的那个。
        // 这里断言的是"最早的那个确实被注册了"，与注册顺序无关。
        repository.save(Alarm(hour = 9, minute = 0))
        repository.save(Alarm(hour = 7, minute = 0))

        scheduler.scheduleAll(mondayMorning)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        val expected = NextTrigger.next(7, 0, RepeatDays.NONE_MASK, mondayMorning)
        assertEquals(expected.toInstant().toEpochMilli(), scheduled!!.triggerAtTime)
        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `删除闹钟会连它的注册一起取消`() = runTest {
        // 这条覆盖的是"幽灵闹钟"：用户删了闹钟，但它仍然在原定时间响。
        // 根因是 scheduleAll 只能取消"数据库里还存在的"闹钟的注册。
        val id = repository.save(Alarm(hour = 7, minute = 0))
        scheduler.scheduleAll(mondayMorning)
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())

        repository.delete(id)

        assertNull(
            "闹钟被删除后不应还留着注册，实际 ${shadowOf(alarmManager).scheduledAlarms.size} 个",
            shadowOf(alarmManager).peekNextScheduledAlarm(),
        )
    }

    @Test
    fun `禁用闹钟后重算会取消它的注册`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        scheduler.scheduleAll(mondayMorning)
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())

        repository.setEnabled(id, false)
        scheduler.scheduleAll(mondayMorning)

        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `重复闹钟注册的是下一个命中的星期`() = runTest {
        val tuesdayMorning = ZonedDateTime.of(2026, 10, 13, 8, 0, 0, 0, zone)   // 周二 08:00
        val mask = RepeatDays.WEEKDAYS_MASK
        repository.save(Alarm(hour = 7, minute = 0, repeatDays = RepeatDays(mask)))

        scheduler.scheduleAll(tuesdayMorning)

        val expected = NextTrigger.next(7, 0, mask, tuesdayMorning)
        assertEquals(
            expected.toInstant().toEpochMilli(),
            shadowOf(alarmManager).peekNextScheduledAlarm()!!.triggerAtTime,
        )
        // 周二 08:00 之后的第一个工作日 07:00 是周三 07:00
        assertEquals(ZonedDateTime.of(2026, 10, 14, 7, 0, 0, 0, zone), expected)
    }

    @Test
    fun `空列表不注册任何闹钟`() = runTest {
        scheduler.scheduleAll(mondayMorning)
        assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
    }
}
