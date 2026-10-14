package com.example.clock.ui.edit

import com.example.clock.data.AlarmDao
import com.example.clock.data.AlarmEntity
import com.example.clock.data.AlarmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * 编辑页 ViewModel 的单元测试。
 *
 * 刻意只用 `alarmId = null`（新建模式）：`init` 只在该参数非空时才 launch，
 * 所以这些用例全程同步、不需要 Robolectric，也不需要 `runTest` ——
 * 只装一个 Main dispatcher 就够（理由见下面的 `setUp`）。
 *
 * `AlarmRepository` 是 final class、没有接口，无法直接手写替身。但它的构造函数
 * 只吃一个 `AlarmDao`，而 `AlarmDao` **是**接口 —— 所以这里伪造 DAO、套一个真的
 * `AlarmRepository`，既保住了"手写替身、不引 mocking 库"，又顺带覆盖了仓储自身
 * 的 `toDomain()` 往返。不需要为了可测性改动任何生产代码。
 *
 * 这里**不**测 `save()` 的 `id == 0` / `id != 0` 两条分支：那是 `AlarmRepository.save`
 * 的路由行为，已由 `AlarmRepositoryTest` 钉住，重复覆盖只会让两边各自僵化。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditViewModelTest {

    /**
     * `viewModelScope` 铺的是 `Dispatchers.Main.immediate`。纯 JVM 单测里没有 Main
     * dispatcher，一旦有代码真的走进 `launch`，抛出来的是
     * "Module with the Main dispatcher had failed to initialize"，断言根本轮不到执行 ——
     * 那样 `delete 是空操作` 这条用例就只是"碰巧崩了"，而不是在钉它声称的行为。
     * 装上 Unconfined 之后 `launch` 内联跑完，于是"有没有碰仓库"可以在调用返回后直接断言。
     */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `新建闹钟默认 7 点整`() {
        val viewModel = AlarmEditViewModel(repository(), alarmId = null)

        assertEquals(7, viewModel.draft.value?.hour)
        assertEquals(0, viewModel.draft.value?.minute)
    }

    @Test
    fun `setHour 把小时钳在 0 到 23`() {
        val viewModel = AlarmEditViewModel(repository(), alarmId = null)

        viewModel.setHour(30)
        assertEquals("超出上界应向 23 收敛", 23, viewModel.draft.value?.hour)

        viewModel.setHour(-1)
        assertEquals("低于下界应向 0 收敛", 0, viewModel.draft.value?.hour)
    }

    @Test
    fun `setMinute 把分钟钳在 0 到 59`() {
        val viewModel = AlarmEditViewModel(repository(), alarmId = null)

        viewModel.setMinute(90)
        assertEquals("超出上界应向 59 收敛", 59, viewModel.draft.value?.minute)

        viewModel.setMinute(-5)
        assertEquals("低于下界应向 0 收敛", 0, viewModel.draft.value?.minute)
    }

    @Test
    fun `alarmId 为空时 delete 是空操作`() {
        val dao = RecordingAlarmDao()
        val viewModel = AlarmEditViewModel(AlarmRepository(dao) {}, alarmId = null)
        var deletedCalled = false

        viewModel.delete(onDeleted = { deletedCalled = true })

        assertFalse("alarmId 为空时不应触发 onDeleted", deletedCalled)
        assertEquals("alarmId 为空时不应碰仓库", emptyList<String>(), dao.calls)
    }

    private fun repository(): AlarmRepository = AlarmRepository(RecordingAlarmDao()) {}

    /** 手写替身：记录每一次 DAO 调用，供"不应触碰仓库"这类断言使用。 */
    private class RecordingAlarmDao : AlarmDao {

        val calls = mutableListOf<String>()

        private val rows = linkedMapOf<Long, AlarmEntity>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<AlarmEntity>> = flowOf(rows.values.toList())

        override suspend fun getAll(): List<AlarmEntity> {
            calls += "getAll"
            return rows.values.toList()
        }

        override suspend fun getEnabled(): List<AlarmEntity> {
            calls += "getEnabled"
            return rows.values.filter { it.enabled }
        }

        override suspend fun getById(id: Long): AlarmEntity? {
            calls += "getById($id)"
            return rows[id]
        }

        override suspend fun insert(entity: AlarmEntity): Long {
            calls += "insert"
            val id = if (entity.id != 0L) entity.id else nextId++
            rows[id] = entity.copy(id = id)
            return id
        }

        override suspend fun update(entity: AlarmEntity) {
            calls += "update"
            rows[entity.id] = entity
        }

        override suspend fun deleteById(id: Long) {
            calls += "deleteById($id)"
            rows.remove(id)
        }
    }
}
