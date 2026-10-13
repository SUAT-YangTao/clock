package com.example.clock.ui.ring

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.clock.alarm.RingAudioController
import com.example.clock.alarm.RingService
import com.example.clock.alarm.RingingAlarm
import com.example.clock.alarm.SnoozeManager
import com.example.clock.ui.theme.ClockTheme

/**
 * 全屏响铃界面。独立 Activity，不参与导航图。
 *
 * 启动 Activity 不受"后台不能启动前台服务"那些限制，因此这是响铃链路里最可靠的
 * 一环 —— AlarmReceiver 会最先拉起它。
 */
class RingActivity : ComponentActivity() {

    // 用 Compose 状态而不是普通字段：manifest 把本 Activity 声明为 singleTask，
    // 第二个闹钟触发时走的是 onNewIntent，只有被 Compose 观察到的状态才能让界面跟着换。
    // 若用普通 var，界面会停在第一个闹钟的时间上，而贪睡按钮读的是字段 ——
    // 结果就是"看到的是 A、贪睡的却是 B"。
    private var ringing by mutableStateOf<RingingAlarm?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏之上直接显示，无需解锁；同时点亮屏幕。
        // 这两个方法是 API 27（O_MR1）才有的，而本项目 minSdk = 26：不加版本判断的话，
        // 在一台 Android 8.0 机器上一响铃就 NoSuchMethodError 崩溃 —— 那是这个 app
        // 最不能崩的时刻。API 26 上退回普通显示即可，不影响"闹钟一定会响"。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val incoming = RingingAlarm.from(intent)
        if (incoming == null) {
            finish()
            return
        }
        ringing = incoming

        setContent {
            ClockTheme {
                // 返回键不关闭响铃：半睡半醒时误触返回键等于把闹钟静音了。
                BackHandler(enabled = true) { /* 刻意留空 */ }

                // 读的是可观察状态，不是 onCreate 里的局部 incoming ——
                // 这样 onNewIntent 换成新闹钟时界面会重新组合。
                ringing?.let {
                    RingScreen(
                        ringing = it,
                        onSnooze = ::snooze,
                        onDismiss = ::dismiss,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        RingingAlarm.from(intent)?.let { ringing = it }
    }

    /**
     * 铃声音频的降级入口。
     *
     * 正常情况下 RingService 已经开始了播放，这里是一次空操作；若前台服务被系统
     * 拒绝启动（见 spec 5.6），这里就是唯一的播放来源。RingAudioController 的幂等性
     * 保证两者不会叠音。
     */
    override fun onStart() {
        super.onStart()
        // 这是响铃的兜底路径，也是最后一道。播放失败时不能让它把 Activity 带崩 ——
        // 界面留着，用户还能贪睡或关闭，总好过弹一个崩溃对话框。
        // 控制器失败时已把状态回滚为"未在响铃"，所以这里吞掉不会留下陈旧状态。
        ringing?.let {
            try {
                RingAudioController.get(this).start(it.vibrate)
            } catch (t: Throwable) {
                Log.w(TAG, "降级播放铃声也失败了", t)
            }
        }
    }

    private fun snooze() {
        ringing?.let { SnoozeManager(this).snooze(it) }
        stopEverything()
    }

    private fun dismiss() {
        stopEverything()
    }

    private fun stopEverything() {
        RingAudioController.get(this).stop()
        stopService(Intent(this, RingService::class.java))
        finish()
    }

    companion object {
        private const val TAG = "RingActivity"

        fun start(context: Context, ringing: RingingAlarm) {
            val intent = Intent(context, RingActivity::class.java).apply {
                // 必须带 NEW_TASK：本方法会被 AlarmReceiver 用 receiver 的 Context 调用，
                // 而从非 Activity 的 Context 启动 Activity 时不带这个 flag，在 targetSdk >= N
                // 时会直接抛 AndroidRuntimeException —— 那正好是闹钟该响的那一刻。
                // manifest 里 RingActivity 是 singleTask + taskAffinity=""，配 NEW_TASK 才是它的用法。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ringing.writeTo(this)
            }
            context.startActivity(intent)
        }
    }
}
