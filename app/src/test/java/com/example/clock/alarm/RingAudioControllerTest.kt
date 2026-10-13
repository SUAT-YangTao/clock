package com.example.clock.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RingAudioControllerTest {

    private class FakePlayer : AlarmPlayer {
        var startCount = 0
        var stopCount = 0
        var lastVibrate: Boolean? = null

        override fun start(vibrate: Boolean) {
            startCount++
            lastVibrate = vibrate
        }

        override fun stop() {
            stopCount++
        }
    }

    private lateinit var player: FakePlayer
    private lateinit var controller: RingAudioController

    @Before
    fun setUp() {
        player = FakePlayer()
        controller = RingAudioController(player)
    }

    @Test
    fun `首次 start 会真正播放`() {
        controller.start(vibrate = true)

        assertEquals(1, player.startCount)
        assertTrue(controller.isRinging)
        assertEquals(true, player.lastVibrate)
    }

    @Test
    fun `重复 start 不会产生第二路铃声`() {
        controller.start(vibrate = true)
        controller.start(vibrate = true)
        controller.start(vibrate = true)

        assertEquals("重复 start 只应真正播放一次", 1, player.startCount)
        assertTrue(controller.isRinging)
    }

    @Test
    fun `重复 stop 不会报错也不会重复停止`() {
        controller.start(vibrate = true)
        controller.stop()
        controller.stop()

        assertEquals(1, player.stopCount)
        assertFalse(controller.isRinging)
    }

    @Test
    fun `未 start 就 stop 是安全的`() {
        controller.stop()
        assertEquals(0, player.stopCount)
        assertFalse(controller.isRinging)
    }

    @Test
    fun `stop 之后可以再次 start`() {
        controller.start(vibrate = true)
        controller.stop()
        controller.start(vibrate = false)

        assertEquals(2, player.startCount)
        assertEquals(1, player.stopCount)
        assertTrue(controller.isRinging)
        assertEquals(false, player.lastVibrate)
    }

    @Test
    fun `震动开关被原样传给播放器`() {
        controller.start(vibrate = false)
        assertEquals(false, player.lastVibrate)

        controller.stop()
        controller.start(vibrate = true)
        assertEquals(true, player.lastVibrate)
    }

    @Test
    fun `多线程并发 start 只播放一次`() {
        val threads = (1..8).map {
            Thread { controller.start(vibrate = true) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("并发 start 也只应播放一次", 1, player.startCount)
    }
}
