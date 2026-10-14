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

    fun repository(context: Context): AlarmRepository {
        // 只捕获 applicationContext：repositoryRef 活到进程结束，闭包捕获谁就一直握着谁。
        // repository() 的第一个调用方是 AlarmListScreen 的 LocalContext.current（Activity），
        // 捕获它等于把那个 Activity 钉在进程里 —— 注意 dao 那一行并不能解决这个问题，
        // AlarmDatabase.get() 内部本来就强制 applicationContext。
        val appContext = context.applicationContext
        return repositoryRef ?: synchronized(this) {
            repositoryRef ?: AlarmRepository(
                dao = AlarmDatabase.get(appContext).alarmDao(),
                onAlarmsChanged = { removedId ->
                    // 删除时先把 id 交给调度器。跳掉这一步，被删闹钟的注册就再也
                    // 取消不掉，会在原定时间照常响 —— 见 AlarmRepository.delete。
                    if (removedId != null) scheduler(appContext).cancel(removedId)
                    scheduler(appContext).scheduleAll()
                },
            ).also { repositoryRef = it }
        }
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
