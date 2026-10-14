package com.example.clock.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.NextTrigger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

data class AlarmListUiState(
    val alarms: List<Alarm> = emptyList(),
    val nextSummary: String? = null,
)

class AlarmListViewModel(private val repository: AlarmRepository) : ViewModel() {

    val uiState: StateFlow<AlarmListUiState> = repository.observeAll()
        .map { alarms -> AlarmListUiState(alarms, buildNextSummary(alarms)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AlarmListUiState(),
        )

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    /**
     * 撤销删除。
     *
     * 能这么做的前提是 [AlarmRepository.save] 在 id 不存在时会按**原 id** 重新插入
     * （见 Task 6）。如果 save 只会 insert 出新 id，"撤销"就会把闹钟变成一条新记录，
     * 而旧 id 对应的 AlarmManager 注册则由 delete 时的 cancel 清掉了 —— 结果就是
     * 撤销后闹钟看着回来了却再也不会响。
     */
    fun restore(alarm: Alarm) {
        viewModelScope.launch { repository.save(alarm) }
    }

    companion object {
        fun factory(repository: AlarmRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AlarmListViewModel(repository) }
        }
    }
}

/**
 * 摘要行的文案。三个分支各是一条明确的契约（见本任务 Interfaces）：
 * 列表为空 -> null（整页走空状态）；列表非空但没有启用的闹钟 -> 「没有启用的闹钟」；
 * 否则 -> 「下一个闹钟：周X HH:MM（剩余…）」。
 *
 * 刻意放在**文件顶层**而不是 ViewModel 的成员：它是纯函数，不需要 `this`，放在顶层就能被
 * 一个不构造 ViewModel、不碰协程的普通 JVM 测试直接调用（见 Step 2）。
 *
 * `now` 可注入，默认取当前时刻；生产调用点不传，测试传固定值以获得确定性 ——
 * 否则第三个分支的期望值会随墙上时钟漂移，这个测试根本没法写。
 *
 * 注意 [NextTrigger.nextOf] 自己就会过滤掉未启用的闹钟（Task 4），所以这里必须传
 * **未过滤**的完整列表；正因如此「没有启用的闹钟」这个分支是靠 nextOf 返回 null 命中的。
 */
internal fun buildNextSummary(
    alarms: List<Alarm>,
    now: ZonedDateTime = ZonedDateTime.now(),
): String? {
    // 列表为空时整页走空状态，不显示摘要行。
    if (alarms.isEmpty()) return null
    val next = NextTrigger.nextOf(alarms, now) ?: return "没有启用的闹钟"
    val weekday = weekdayName(next.at.dayOfWeek.value)
    val time = "%02d:%02d".format(next.at.hour, next.at.minute)
    return "下一个闹钟：$weekday $time（${NextTrigger.formatRemaining(now, next.at)}）"
}

private fun weekdayName(value: Int): String = when (value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}
