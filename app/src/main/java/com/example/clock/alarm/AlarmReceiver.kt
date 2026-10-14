package com.example.clock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.clock.AppGraph
// 分层例外（全局约束里 alarm/ 不得依赖 ui/ 的唯一一处）：
// 启动 Activity 必须用显式 Intent，才能保证 AlarmManager 唤醒的那一瞬间不被隐式
// Intent 解析延迟拖慢 —— 响铃延迟是闹钟 app 的核心体验指标。不要为了"修"这条约束
// 改用反射或 action 匹配，那只会引入新的失败点。
import com.example.clock.ui.ring.RingActivity
import kotlinx.coroutines.runBlocking

/**
 * 闹钟到点时的入口。
 *
 * 执行顺序是刻意安排的：
 * 1. 先拉起全屏界面 —— 这是唯一不受后台限制的一步，失败概率最低，也就最该先做
 * 2. 再尝试启动前台服务，失败只降级不中断（见 spec 5.6）
 * 3. 最后做数据库善后：关闭一次性闹钟、给重复闹钟排下一次
 */
class AlarmReceiver : BroadcastReceiver() {

    /**
     * 前台服务启动的注入点。测试把它换成会抛异常的 lambda，就能验证
     * "服务起不来时流程仍然走完"这条降级路径 —— 这条路径在真机上几乎
     * 无法复现（见 spec 5.6 与 Google Issue Tracker #238340795）。
     *
     * 用实例字段而不是全局 object：Receiver 每次广播都会新建实例，
     * 但测试里是直接 new 一个来调用的，所以替换字段即可，且不会在
     * 测试之间泄漏状态。
     */
    @VisibleForTesting
    internal var serviceStarter: (Context, RingingAlarm) -> Unit = RingService::start

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE_ALARM) return

        val ringing = RingingAlarm.from(intent)
        if (ringing == null) {
            Log.w(TAG, "收到的响铃 Intent 数据不完整，忽略：$intent")
            return
        }

        val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_SNOOZE, false)

        // 1. 先把界面拉起来。多余的耗时操作都排在它后面。
        try {
            RingActivity.start(context, ringing)
        } catch (e: Exception) {
            // 后台启动 Activity 在个别 ROM 上会被拒绝；此时通知里的 fullScreenIntent
            // 和前台服务仍然是可用的，所以不中断流程。
            Log.w(TAG, "启动响铃界面失败，依赖通知继续响铃", e)
        }

        // 2. 尝试前台服务。失败时铃声由 RingActivity 播放 —— RingAudioController
        //    是幂等的，两条路径不会叠音。
        try {
            serviceStarter(context, ringing)
        } catch (e: Exception) {
            Log.w(TAG, "前台服务启动失败，降级为由响铃界面播放铃声", e)
        }

        // 3. 数据库善后。用 runBlocking 而非 goAsync()+协程：这里只有一两次极小的
        //    事务，同步做完可以让"响铃后必然完成重算"这件事变得无需推理。
        if (!isSnooze) {
            runBlocking {
                try {
                    val repository = AppGraph.repository(context)
                    val alarm = repository.getById(ringing.id)

                    if (alarm != null && alarm.repeatDays.isOnceOnly) {
                        // 一次性闹钟响完必须自己关掉，否则明天同一时间还会响。
                        repository.disableSilently(ringing.id)
                    }
                    AppGraph.scheduler(context).scheduleAll()
                } catch (e: Exception) {
                    Log.e(TAG, "响铃后的重算失败", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
