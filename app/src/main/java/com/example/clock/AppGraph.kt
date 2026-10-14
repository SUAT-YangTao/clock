package com.example.clock

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.clock.alarm.AlarmScheduler
import com.example.clock.data.AlarmDatabase
import com.example.clock.data.AlarmRepository

/**
 * 手写的依赖装配。
 *
 * 只有两个对象（Repository、Scheduler），引入 Hilt 的收益远小于它的复杂度。
 */
object AppGraph {

    @Volatile
    private var repositoryRef: AlarmRepository? = null

    @Volatile
    private var schedulerRef: AlarmScheduler? = null

    fun repository(context: Context): AlarmRepository =
        repositoryRef ?: synchronized(this) {
            repositoryRef ?: AlarmRepository(
                // 用 applicationContext：repositoryRef 活到进程结束，若钉住调用方
                // （第一个调用方是 LocalContext.current，即 Activity）就等于泄漏一个
                // Activity。同文件的 scheduler() 早就用的是 applicationContext。
                dao = AlarmDatabase.get(context.applicationContext).alarmDao(),
                onAlarmsChanged = { removedId ->
                    // 删除时先把 id 交给调度器。跳掉这一步，被删闹钟的注册就再也
                    // 取消不掉，会在原定时间照常响 —— 见 AlarmRepository.delete。
                    if (removedId != null) scheduler(context).cancel(removedId)
                    scheduler(context).scheduleAll()
                },
            ).also { repositoryRef = it }
        }

    fun scheduler(context: Context): AlarmScheduler =
        schedulerRef ?: synchronized(this) {
            schedulerRef ?: AlarmScheduler(
                context = context.applicationContext,
                repository = repository(context),
            ).also { schedulerRef = it }
        }

    /** 仅供测试：清空全部单例。理由见 Task 6 的同名函数。 */
    @VisibleForTesting
    fun resetForTesting() {
        synchronized(this) {
            repositoryRef = null
            schedulerRef = null
        }
        AlarmDatabase.resetForTesting()
    }
}
