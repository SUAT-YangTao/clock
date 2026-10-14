package com.example.clock.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.clock.domain.RepeatDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingNotificationTest {

    private lateinit var context: Context

    private val ringing = RingingAlarm(
        id = 3L,
        hour = 7,
        minute = 30,
        label = "起床",
        vibrate = true,
        repeatDays = RepeatDays.NONE,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `ensureChannel 创建了响铃通知渠道`() {
        RingNotification.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RingNotification.CHANNEL_ID)
        assertNotNull("响铃渠道应当被创建", channel)
    }

    @Test
    fun `渠道的重要性足以横幅提示`() {
        RingNotification.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RingNotification.CHANNEL_ID)!!
        assertTrue(
            "响铃通知必须能横幅提示，实际 importance=${channel.importance}",
            channel.importance >= NotificationManager.IMPORTANCE_HIGH,
        )
    }

    @Test
    fun `重复 ensureChannel 不抛异常`() {
        RingNotification.ensureChannel(context)
        RingNotification.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(RingNotification.CHANNEL_ID))
    }

    @Test
    fun `通知内容含闹钟时间与标签`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(context, ringing)

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        assertTrue("标题应含时间，实际为 $title", title.contains("07:30"))
        assertTrue("正文应含标签，实际为 $text", text.contains("起床"))
    }

    @Test
    fun `通知带两个操作按钮`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(context, ringing)

        assertEquals(2, notification.actions.size)
    }

    @Test
    fun `通知是常驻的且分类为闹钟`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(context, ringing)

        assertTrue("应是常驻通知", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
    }

    @Test
    fun `通知设置了全屏意图以便在锁屏上弹出`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(context, ringing)

        assertNotNull("必须设置 fullScreenIntent", notification.fullScreenIntent)
    }

    @Test
    fun `标签为空时不显示空的正文行`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(
            context,
            ringing.copy(label = ""),
        )
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("正文不应为空，实际为 '$text'", text.isNotBlank())
    }
}
