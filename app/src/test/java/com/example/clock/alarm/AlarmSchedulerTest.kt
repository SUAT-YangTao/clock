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
        // 显式传入缝隙：Robolectric 的 ShadowAlarmManager 对 canScheduleExactAlarms()
        // 的行为不可依赖，一旦走到默认 lambda 就可能把整个类带崩。
        scheduler = AlarmScheduler(context, repository, canScheduleExactAlarms = { true })
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

    @Test
    fun `精确闹钟权限被撤销时不抛异常也不注册`() = runTest {
        // 模拟 Android 12/12L 上用户在「闹钟和提醒」里撤销权限：setAlarmClock
        // 会抛 SecurityException，这里必须在调用它之前就跳过。
        scheduler = AlarmScheduler(context, repository, canScheduleExactAlarms = { false })
        repository.save(Alarm(hour = 7, minute = 0))

        scheduler.scheduleAll(mondayMorning)   // 抛异常即测试失败

        assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `一个闹钟注册失败不影响其余闹钟`() = runTest {
        // 播种用的是 setUp 里那个恒真的缝隙，免得 save() 的回调提前消耗掉
        // 下面那唯一一次"失败"。
        repository.save(Alarm(hour = 9, minute = 0))
        repository.save(Alarm(hour = 7, minute = 0))

        // 第 1 次权限检查就抛异常，之后恒真：钉的是"处理第 N 个闹钟时抛出的异常，
        // 不会阻止第 N+1 个被注册"。getEnabled() 没有 ORDER BY，SQLite 按 rowid
        // （插入顺序）返回，所以先被处理的是先插入的 9:00；它失败后，后插入的
        // 7:00 仍被注册 —— 断言恰好 1 个且是 7:00，正是这个意思。
        var calls = 0
        scheduler = AlarmScheduler(context, repository, canScheduleExactAlarms = {
            calls++
            if (calls == 1) throw IllegalStateException("模拟处理第一个闹钟时失败")
            true
        })

        scheduler.scheduleAll(mondayMorning)

        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertEquals(
            NextTrigger.next(7, 0, RepeatDays.NONE_MASK, mondayMorning).toInstant().toEpochMilli(),
            scheduled!!.triggerAtTime,
        )
    }
}
