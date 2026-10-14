package com.example.clock.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.clock.AppGraph
import com.example.clock.domain.RepeatDays
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarmId: Long?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: AlarmEditViewModel = viewModel(
        key = "edit-${alarmId ?: 0L}",
        factory = AlarmEditViewModel.factory(AppGraph.repository(context), alarmId),
    )
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    val timePickerState = rememberTimePickerState(
        initialHour = draft?.hour ?: 7,
        initialMinute = draft?.minute ?: 0,
        is24Hour = true,
    )

    // 编辑既有闹钟时，数据是异步读出来的，需要把选择器同步过去一次。
    LaunchedEffect(draft?.id) {
        draft?.let {
            timePickerState.hour = it.hour
            timePickerState.minute = it.minute
        }
    }

    // 用户拨动表盘后写回草稿。
    LaunchedEffect(timePickerState.hour, timePickerState.minute) {
        viewModel.setHour(timePickerState.hour)
        viewModel.setMinute(timePickerState.minute)
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    // spec 6.2 要求表盘与数字输入两种模式可切换。两个 Composable 共用同一个
    // timePickerState，所以来回切换不会丢已经选好的时间。
    var useDial by remember { mutableStateOf(true) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (alarmId == null) "新建闹钟" else "编辑闹钟") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (alarmId != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = draft
        if (current == null) {
            Box(Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (useDial) {
                TimePicker(state = timePickerState)
            } else {
                TimeInput(state = timePickerState)
            }

            TextButton(
                onClick = { useDial = !useDial },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (useDial) "键盘输入" else "表盘")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "重复",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = current.repeatDays.includes(day),
                        onClick = { viewModel.toggleDay(day) },
                        label = { Text(shortName(day)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = current.repeatDays.mask == RepeatDays.WEEKDAYS_MASK,
                    onClick = { viewModel.applyPreset(RepeatDays.WEEKDAYS) },
                    label = { Text("工作日") },
                )
                FilterChip(
                    selected = current.repeatDays.mask == RepeatDays.EVERY_DAY_MASK,
                    onClick = { viewModel.applyPreset(RepeatDays.EVERY_DAY) },
                    label = { Text("每天") },
                )
                FilterChip(
                    selected = current.repeatDays.isOnceOnly,
                    onClick = { viewModel.applyPreset(RepeatDays.NONE) },
                    label = { Text("仅一次") },
                )
            }

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = current.label,
                onValueChange = viewModel::setLabel,
                label = { Text("标签（可留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("震动", modifier = Modifier.weight(1f))
                Switch(checked = current.vibrate, onCheckedChange = viewModel::setVibrate)
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { viewModel.save(onSaved = onDone) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("保存")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这个闹钟？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete(onDeleted = onDone)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

private fun shortName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}
