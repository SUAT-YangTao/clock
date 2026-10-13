package com.example.clock.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 铃声播放的**单一持有者**。
 *
 * 前台服务（RingService）和全屏界面（RingActivity）都会调用 [start] —— 谁先到
 * 谁真正播放，后到的是一次空操作。这是"不许出现两路铃声重叠"这一约束的实现方式，
 * 也是前台服务启动失败时能降级给 Activity 播放的前提。
 */
class RingAudioController(private val player: AlarmPlayer) {

    private val lock = Any()
    private var ringing = false

    val isRinging: Boolean get() = synchronized(lock) { ringing }

    /** 开始响铃。已在响铃时是空操作。 */
    fun start(vibrate: Boolean) {
        synchronized(lock) {
            if (ringing) return
            // 先把标志置为"响铃中"再真正播放。顺序不能反：mediaPlayer.start() 已经播出
            // 声音之后，startVibrating() 仍可能抛异常（例如震动权限缺失）。若标志还是
            // false，stop() 会在 `if (!ringing) return` 上提前返回，用户就关不掉这通铃声了。
            ringing = true
            try {
                player.start(vibrate)
            } catch (t: Throwable) {
                // 播放器可能已经播了一半，尽力收尾，回滚标志，再把异常原样抛出。
                runCatching { player.stop() }
                ringing = false
                throw t
            }
        }
    }

    /** 停止响铃。本来就没在响时是空操作。 */
    fun stop() {
        synchronized(lock) {
            if (!ringing) return
            player.stop()
            ringing = false
        }
    }

    companion object {
        @Volatile
        private var instance: RingAudioController? = null

        fun get(context: Context): RingAudioController =
            instance ?: synchronized(this) {
                instance ?: RingAudioController(
                    MediaPlayerAlarmPlayer(context.applicationContext),
                ).also { instance = it }
            }
    }
}

/**
 * 用 MediaPlayer 播放系统默认闹钟铃声，并用 Vibrator 震动。
 */
class MediaPlayerAlarmPlayer(private val context: Context) : AlarmPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun start(vibrate: Boolean) {
        // 先释放上一轮，避免"上一次铃声还在响时新的触发到来"造成叠音。
        releasePlayer()

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        try {
            // 先把实例赋给字段，再调用会抛异常的 setDataSource / prepare / start。
            // 不能写成 `mediaPlayer = MediaPlayer().apply { ... }`：apply 里任何一步抛异常，
            // 整个赋值都会被跳过，字段停在 null，新建的 MediaPlayer 从此无人能释放，
            // 反复失败会一直占着音频编解码器。
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            if (vibrate) startVibrating()
        } catch (t: Throwable) {
            // 半途失败也要把已经建好的 MediaPlayer 放掉，再把异常原样抛出。
            releasePlayer()
            stopVibrating()
            throw t
        }
    }

    override fun stop() {
        releasePlayer()
        stopVibrating()
    }

    private fun releasePlayer() {
        mediaPlayer?.run {
            try {
                if (isPlaying) stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "停止 MediaPlayer 时状态异常，忽略", e)
            }
            release()
        }
        mediaPlayer = null
    }

    private fun startVibrating() {
        val v = obtainVibrator() ?: return
        vibrator = v
        // 响 1 秒、停 1 秒，循环往复。
        val pattern = longArrayOf(0L, 1000L, 1000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, 0)
        }
    }

    private fun stopVibrating() {
        vibrator?.cancel()
        vibrator = null
    }

    private fun obtainVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private companion object {
        const val TAG = "MediaPlayerAlarmPlayer"
    }
}
