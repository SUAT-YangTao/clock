package com.example.clock.data

import com.example.clock.domain.Alarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 闹钟数据的唯一出入口。对外只暴露 [Alarm]，界面层与 alarm 层都看不到 [AlarmEntity]。
 *
 * [onAlarmsChanged] 是"闹钟变了，请重算注册"的钩子。用回调而不是直接依赖
 * AlarmScheduler，是为了保持依赖方向单一（data 不 import alarm），同时保证
 * 任何一处改动都不可能忘记触发重算。
 *
 * 回调带一个 [Long]? 参数：**删除时传入被删的 id，其余情况传 null**。
 * 这个参数不是可选的便利，而是正确性所必需的 —— 见 [delete] 的说明。
 */
class AlarmRepository(
    private val dao: AlarmDao,
    private val onAlarmsChanged: suspend (removedId: Long?) -> Unit,
) {

    fun observeAll(): Flow<List<Alarm>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getAll(): List<Alarm> = dao.getAll().map { it.toDomain() }

    suspend fun getEnabled(): List<Alarm> = dao.getEnabled().map { it.toDomain() }

    suspend fun getById(id: Long): Alarm? = dao.getById(id)?.toDomain()

    /**
     * 新增或更新（upsert）。返回闹钟 id。
     *
     * id 非 0 但数据库里查不到时走 insert 而不是 update：`@Update` 匹配不到行时
     * 会**静默影响 0 行**，闹钟就此凭空消失且没有任何报错。列表页的"撤销删除"
     * 正依赖这条语义。
     */
    suspend fun save(alarm: Alarm): Long {
        val id = when {
            alarm.id == 0L -> dao.insert(alarm.toEntity())
            dao.getById(alarm.id) == null -> dao.insert(alarm.toEntity())
            else -> {
                dao.update(alarm.toEntity())
                alarm.id
            }
        }
        onAlarmsChanged(null)
        return id
    }

    /**
     * 删除闹钟。
     *
     * **必须把 id 交给回调。** `AlarmScheduler.scheduleAll()` 是靠遍历数据库里的
     * 闹钟来取消注册的；一旦行被删掉，调度器就再也拼不出这个闹钟的 PendingIntent，
     * 那个已经注册的闹钟**再也无法取消**，会在原定时间照常响 —— 用户明明删了它。
     * 因此 id 必须在删除时被显式传递出去。
     */
    suspend fun delete(id: Long) {
        dao.deleteById(id)
        onAlarmsChanged(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(enabled = enabled))
        onAlarmsChanged(null)
    }

    /**
     * 把一次性闹钟关掉，但**不**触发重算。
     *
     * 供 AlarmReceiver 使用：响铃后它自己会调 scheduleAll()，若这里也触发一次
     * 就是重复劳动，而且会让"响铃后重算"的调用时机变得难以追踪。
     */
    suspend fun disableSilently(id: Long) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(enabled = false))
    }
}
