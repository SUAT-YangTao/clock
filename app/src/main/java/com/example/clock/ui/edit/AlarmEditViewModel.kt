package com.example.clock.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

class AlarmEditViewModel(
    private val repository: AlarmRepository,
    private val alarmId: Long?,
) : ViewModel() {

    private val _draft = MutableStateFlow<Alarm?>(defaultDraft())
    val draft: StateFlow<Alarm?> = _draft.asStateFlow()

    init {
        if (alarmId != null) {
            viewModelScope.launch {
                repository.getById(alarmId)?.let { _draft.value = it }
            }
        }
    }

    fun setHour(hour: Int) = update { it.copy(hour = hour.coerceIn(0, 23)) }

    fun setMinute(minute: Int) = update { it.copy(minute = minute.coerceIn(0, 59)) }

    fun setLabel(label: String) = update { it.copy(label = label) }

    fun setVibrate(vibrate: Boolean) = update { it.copy(vibrate = vibrate) }

    fun toggleDay(day: DayOfWeek) = update { it.copy(repeatDays = it.repeatDays.toggle(day)) }

    fun applyPreset(preset: RepeatDays) = update { it.copy(repeatDays = preset) }

    fun save(onSaved: () -> Unit) {
        val alarm = _draft.value ?: return
        viewModelScope.launch {
            repository.save(alarm)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = alarmId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }

    private fun update(transform: (Alarm) -> Alarm) {
        _draft.value = _draft.value?.let(transform)
    }

    private fun defaultDraft(): Alarm {
        // 新建时默认 7:00、仅一次、开启震动——这是最常见的起床闹钟形态。
        val now = LocalTime.now()
        return Alarm(
            hour = if (alarmId == null) 7 else now.hour,
            minute = if (alarmId == null) 0 else now.minute,
        )
    }

    companion object {
        fun factory(repository: AlarmRepository, alarmId: Long?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { AlarmEditViewModel(repository, alarmId) }
            }
    }
}
