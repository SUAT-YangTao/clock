package com.example.clock

import android.content.Context
import androidx.annotation.VisibleForTesting
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

    fun repository(context: Context): AlarmRepository =
        repositoryRef ?: synchronized(this) {
            repositoryRef ?: AlarmRepository(
                dao = AlarmDatabase.get(context).alarmDao(),
                // Task 7 会把它换成 { scheduler(context).scheduleAll() }
                onAlarmsChanged = { },
            ).also { repositoryRef = it }
        }

    /**
     * 仅供测试：清空全部单例。
     *
     * Robolectric 每个测试都新建 Application，但 object 的静态字段不会重置。
     * 不调用它，后一个测试会拿到上一个测试遗留的、绑定了失效 Context 的实例。
     */
    @VisibleForTesting
    fun resetForTesting() {
        synchronized(this) {
            repositoryRef = null
        }
        AlarmDatabase.resetForTesting()
    }
}
