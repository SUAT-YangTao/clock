package com.example.clock.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.clock.AppGraph
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class SystemEventReceiverTest {

    private lateinit var context: Context
    private var seededId: Long = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppGraph.resetForTesting()
        // 本类经由 AppGraph 间接构造 AlarmScheduler（没有注入缝隙，走读系统的默认
        // lambda）。Robolectric 的 ShadowAlarmManager 默认报告"精确闹钟权限不可用"，
        // AlarmScheduler.schedule() 会因此直接跳过注册。显式打开它，否则下面
        // "应当触发重算注册"的断言全部落空 —— 且是假失败。
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        seededId = runBlocking {
            AppGraph.repository(context).save(
                Alarm(hour = 7, minute = 0, repeatDays = RepeatDays(RepeatDays.EVERY_DAY_MASK)),
            )
        }
        // 播种本身会经由 AppGraph 的 onAlarmsChanged 回调注册一次闹钟。这里必须把它
        // 取消掉：本类的三个用例断言的都是"Receiver 有没有触发重算"，只有从
        // "数据库里有闹钟、但没有任何注册"这个起点出发才成立。否则注册一直在，
        // 三个用例都恒真 —— 把 SystemEventReceiver.onReceive 清空也照样通过。
        AppGraph.scheduler(context).cancel(seededId)
        // 静态方法，不能经由 shadowOf(...) 的实例调用。
        ShadowAlarmManager.setAutoSchedule(false)
    }

    private fun hasScheduledAlarm(): Boolean =
        shadowOf(context.getSystemService(AlarmManager::class.java))
            .peekNextScheduledAlarm() != null

    /** Review Focus 第 3 条：这四种系统事件任何一个漏接，闹钟都会静默失效。 */
    private val reschedulingActions = listOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
    )

    @Test
    fun `全部系统事件都会触发重算`() {
        for (action in reschedulingActions) {
            // 每轮都退回"没有任何注册"的起点，否则第 2~4 个 action 会靠上一轮留下的
            // 注册蒙混过关 —— 那样这个循环实际只验证了第一个 action。
            AppGraph.scheduler(context).cancel(seededId)
            ShadowAlarmManager.setAutoSchedule(false)

            SystemEventReceiver().onReceive(context, Intent(action))

            assertTrue("$action 应当触发重算注册", hasScheduledAlarm())
        }
    }

    @Test
    fun `无关的 action 不触发重算`() {
        SystemEventReceiver().onReceive(context, Intent("com.example.clock.IRRELEVANT"))
        assertTrue(
            "无关广播不应注册闹钟",
            !hasScheduledAlarm(),
        )
    }

    @Test
    fun `null action 不崩溃`() {
        SystemEventReceiver().onReceive(context, Intent())
        assertTrue(!hasScheduledAlarm())
    }
}
