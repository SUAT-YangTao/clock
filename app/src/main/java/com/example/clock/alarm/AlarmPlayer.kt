package com.example.clock.alarm

/**
 * 播放铃声与震动的抽象。
 *
 * 抽出这个接口的唯一目的是让 [RingAudioController] 的幂等性可以在 JVM 上测试 ——
 * MediaPlayer 与 Vibrator 在单元测试里都不可用。
 */
interface AlarmPlayer {
    fun start(vibrate: Boolean)
    fun stop()
}
