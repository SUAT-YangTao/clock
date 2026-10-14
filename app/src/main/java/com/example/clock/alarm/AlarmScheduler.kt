package com.example.clock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.NextTrigger
import java.time.ZonedDateTime

/**
 * 闹钟调度。**这是唯一的重算入口。**
 *
 * 做的永远是同一件事：读数据库所有已启用的闹钟 → 逐个算下次触发时刻 →
 * 用 setAlarmClock() 注册。
 *
 * 它有且仅有 6 个调用时机（全部通过 scheduleAll()）：开机、应用更新、改时间、
 * 改时区、闹钟增删改、闹钟响铃后。
 */
class AlarmScheduler(
    private val context: Context,
    private val repository: AlarmRepository,
    /**
     * 精确闹钟权限检查。默认读系统；测试注入它来模拟"用户撤销了权限"。
     * 默认值只引用 context，不在构造期碰 alarmManager（那是 body 里的属性）。
     */
    private val canScheduleExactAlarms: () -> Boolean = {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    },
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    /**
     * 清掉全部陈旧注册，再为所有已启用的闹钟重新注册。
     *
     * 先取消全部（含已禁用的）再注册，是为了保证任何残留的旧注册都不会
     * 在用户改完设置后仍然生效。
     *
     * 每一步都逐闹钟隔离：任何一个闹钟抛异常都不能连累其余 ——
     * 否则一次失败就是全线静默不响。
     */
    suspend fun scheduleAll(now: ZonedDateTime = ZonedDateTime.now()) {
        repository.getAll().forEach { alarm ->
            try {
                cancel(alarm.id)
            } catch (e: Exception) {
                Log.e(TAG, "取消闹钟 ${alarm.id} 的旧注册失败，继续处理其余闹钟", e)
            }
        }
        repository.getEnabled().forEach { alarm ->
            try {
                schedule(alarm, now)
            } catch (e: Exception) {
                // 一个闹钟注册失败不能连累其余 —— 否则一次失败就是全线静默不响。
                Log.e(TAG, "闹钟 ${alarm.id} 注册失败，继续注册其余闹钟", e)
            }
        }
    }

    /** 为单个闹钟注册下次触发。 */
    fun schedule(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()) {
        if (!canScheduleExactAlarms()) {
            // 权限被撤销（Android 12/12L 上用户可在「闹钟和提醒」里关掉）。
            // 此时 setAlarmClock 会抛 SecurityException —— 这里跳过并记 ERROR，
            // 而不是让它炸穿调用方。代价是这个闹钟不会响，所以必须是 ERROR。
            Log.e(TAG, "精确闹钟权限不可用，跳过闹钟 ${alarm.id}：它到点不会响")
            return
        }

        val triggerAt = NextTrigger.next(alarm.hour, alarm.minute, alarm.repeatDays.mask, now)

        // FLAG_UPDATE_CURRENT 下 getBroadcast 必然返回非 null，这里的 ?: return
        // 只是为了让可空类型收敛，不是真实的失败分支。
        val pi = pendingIntent(alarm, PendingIntent.FLAG_UPDATE_CURRENT) ?: return

        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt.toInstant().toEpochMilli(), showIntent()),
                pi,
            )
        } catch (e: SecurityException) {
            // 权限可能在上面的检查与这次调用之间被撤销，这层是兜底。
            Log.e(TAG, "注册闹钟 ${alarm.id} 失败：精确闹钟权限不可用", e)
        }
    }

    /** 取消某个闹钟的注册。不存在时静默返回。 */
    fun cancel(alarmId: Long) {
        val pi = pendingIntent(alarmId, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /**
     * 点击系统状态栏闹钟图标时打开的界面。
     *
     * 刻意用 getLaunchIntentForPackage 而不是直接引用 MainActivity —— 后者会让
     * alarm 层依赖 ui 层，破坏本项目的分层约束。
     */
    private fun showIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun pendingIntent(alarm: Alarm, extraFlags: Int): PendingIntent? =
        pendingIntentInternal(alarm.id, alarm, extraFlags)

    private fun pendingIntent(alarmId: Long, extraFlags: Int): PendingIntent? =
        pendingIntentInternal(alarmId, null, extraFlags)

    private fun pendingIntentInternal(
        alarmId: Long,
        alarm: Alarm?,
        extraFlags: Int,
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE_ALARM
            // 唯一的 data URI：与 requestCode 一起保证每个闹钟的 PendingIntent 相互独立，
            // 否则所有闹钟会共用同一个，改一个等于改全部。
            data = Uri.parse("clock://alarm/$alarmId")
            putExtra(EXTRA_ALARM_ID, alarmId)
            alarm?.let {
                putExtra(EXTRA_HOUR, it.hour)
                putExtra(EXTRA_MINUTE, it.minute)
                putExtra(EXTRA_LABEL, it.label)
                putExtra(EXTRA_VIBRATE, it.vibrate)
                putExtra(EXTRA_REPEAT_MASK, it.repeatDays.mask)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            extraFlags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"

        const val ACTION_FIRE_ALARM = "com.example.clock.action.FIRE_ALARM"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_HOUR = "extra_hour"
        const val EXTRA_MINUTE = "extra_minute"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_VIBRATE = "extra_vibrate"
        const val EXTRA_REPEAT_MASK = "extra_repeat_mask"
        const val EXTRA_IS_SNOOZE = "extra_is_snooze"

        private const val SHOW_REQUEST_CODE = 99_999
    }
}
