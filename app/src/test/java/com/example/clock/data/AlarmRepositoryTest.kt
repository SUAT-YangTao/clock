package com.example.clock.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.clock.AppGraph
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
class AlarmRepositoryTest {

    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmRepository
    private var changeCallbackCount = 0
    private val removedIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        // Robolectric 每个测试都新建 Application，但 object / companion object 里的
        // 静态状态不会随之重置。不在这里清空，后一个测试就会拿到上一个测试遗留的
        // 数据库实例与依赖图。
        AlarmDatabase.resetForTesting()
        AppGraph.resetForTesting()

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlarmDatabase::class.java,
        ).allowMainThreadQueries().build()

        changeCallbackCount = 0
        removedIds.clear()
        repository = AlarmRepository(db.alarmDao()) { removedId ->
            changeCallbackCount++
            if (removedId != null) removedIds += removedId
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `新建闹钟后能查回来`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 30, label = "起床"))
        val loaded = repository.getById(id)
        assertEquals(7, loaded?.hour)
        assertEquals(30, loaded?.minute)
        assertEquals("起床", loaded?.label)
    }

    @Test
    fun `保存既有闹钟是更新而不是新增`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0, label = "旧"))
        repository.save(Alarm(id = id, hour = 8, minute = 15, label = "新"))

        val all = repository.getAll()
        assertEquals(1, all.size)
        assertEquals(8, all[0].hour)
        assertEquals("新", all[0].label)
    }

    @Test
    fun `删除后查不到`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        repository.delete(id)
        assertNull(repository.getById(id))
        assertTrue(repository.getAll().isEmpty())
    }

    @Test
    fun `用已删除的 id 保存会重新插入而不是静默丢失`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0, label = "起床"))
        repository.delete(id)

        // 列表页"撤销删除"走的就是这条路径
        val restored = repository.save(Alarm(id = id, hour = 7, minute = 0, label = "起床"))

        assertEquals(1, repository.getAll().size)
        assertEquals("起床", repository.getById(restored)?.label)
    }

    @Test
    fun `getEnabled 只返回已启用的闹钟`() = runTest {
        repository.save(Alarm(hour = 7, minute = 0, enabled = true))
        repository.save(Alarm(hour = 8, minute = 0, enabled = false))
        repository.save(Alarm(hour = 9, minute = 0, enabled = true))

        val enabled = repository.getEnabled()
        assertEquals(2, enabled.size)
        assertTrue(enabled.all { it.enabled })
    }

    @Test
    fun `getAll 包含已禁用的闹钟`() = runTest {
        repository.save(Alarm(hour = 7, minute = 0, enabled = true))
        repository.save(Alarm(hour = 8, minute = 0, enabled = false))
        assertEquals(2, repository.getAll().size)
    }

    @Test
    fun `setEnabled 切换开关`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0, enabled = true))
        repository.setEnabled(id, false)
        assertEquals(false, repository.getById(id)?.enabled)
        repository.setEnabled(id, true)
        assertEquals(true, repository.getById(id)?.enabled)
    }

    @Test
    fun `掩码完整往返`() = runTest {
        val mask = RepeatDays.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY).mask
        val id = repository.save(Alarm(hour = 7, minute = 0, repeatDays = RepeatDays(mask)))
        assertEquals(mask, repository.getById(id)?.repeatDays?.mask)
    }

    @Test
    fun `变更会触发重算回调`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        assertEquals(1, changeCallbackCount)

        repository.setEnabled(id, false)
        assertEquals(2, changeCallbackCount)

        repository.delete(id)
        assertEquals(3, changeCallbackCount)
    }

    @Test
    fun `删除时把被删的 id 交给回调`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        repository.delete(id)

        assertEquals(
            "删除必须把 id 传出去，否则已注册的闹钟再也无法取消",
            listOf(id),
            removedIds,
        )
    }

    @Test
    fun `新增和修改时回调不带 removedId`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        repository.setEnabled(id, false)

        assertTrue("只有删除才应传出 id，实际 $removedIds", removedIds.isEmpty())
    }

    @Test
    fun `disableSilently 不触发重算回调`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        val before = changeCallbackCount

        repository.disableSilently(id)

        assertEquals(before, changeCallbackCount)
        assertEquals(false, repository.getById(id)?.enabled)
    }

    @Test
    fun `observeAll 按时间排序`() = runTest {
        repository.save(Alarm(hour = 9, minute = 0))
        repository.save(Alarm(hour = 6, minute = 30))
        repository.save(Alarm(hour = 7, minute = 0))

        val list = repository.observeAll().first()
        assertEquals(listOf(6 to 30, 7 to 0, 9 to 0), list.map { it.hour to it.minute })
    }
}
