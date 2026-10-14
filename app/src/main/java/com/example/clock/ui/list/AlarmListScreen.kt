@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.clock.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.clock.AppGraph
import com.example.clock.domain.Alarm
import kotlinx.coroutines.launch

@Composable
fun AlarmListScreen(
    onAddAlarm: () -> Unit,
    onEditAlarm: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: AlarmListViewModel = viewModel(
        factory = AlarmListViewModel.factory(AppGraph.repository(context)),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 删除是立即生效的，撤销靠 Snackbar。之所以敢这么做，是因为 save() 能按原 id
    // 复原（见 AlarmListViewModel.restore 的注释）。
    val deleteWithUndo: (Alarm) -> Unit = { alarm ->
        viewModel.delete(alarm.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已删除「${alarm.displayName()}」",
                actionLabel = "撤销",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restore(alarm)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("闹钟") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAlarm) {
                Icon(Icons.Filled.Add, contentDescription = "添加闹钟")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 列表为空时整页走空状态，这一行不显示。
            uiState.nextSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (uiState.alarms.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.alarms, key = { it.id }) { alarm ->
                        SwipeToDeleteRow(onDelete = { deleteWithUndo(alarm) }) {
                            AlarmCard(
                                alarm = alarm,
                                onClick = { onEditAlarm(alarm.id) },
                                onToggle = { enabled -> viewModel.setEnabled(alarm.id, enabled) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "还没有闹钟，点右下角添加",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 左滑（从右往左）触发删除。反向滑动被禁用，避免和系统的返回手势打架。
 *
 * 删除后卡片会从列表里消失，但 **state 不会随之消失**：LazyColumn 的
 * LazySaveableStateHolder 会把离开组合的 item 的 saved state 记在
 * previouslyComposedKeys 里并**保留在内存中**（唯一读它的 performSave() 同时
 * 也是唯一调用 removeState 的地方，所以没有任何东西会剪枝）。撤销把同一个
 * alarm.id 恢复回列表时，那一行的 swipe 状态会被还原成 EndToStart，界面呈现
 * 为"已经划走的样子"（只剩红色删除底色）。因此下面在行重新进入组合时重置一次。
 */
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    // LazyColumn 的 saveable state holder 会把已移除 item 的 saved state 留在内存里，
    // 撤销恢复同一 id 的那一行时会被还原成"已划走"。进入组合时重置一次，让它回到未划走。
    LaunchedEffect(Unit) { state.reset() }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 已关闭的闹钟整体淡化，一眼能区分
            .alpha(if (alarm.enabled) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "%02d:%02d".format(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildSubtitle(alarm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

/** 删除提示里用的名字：有标签用标签，没有就用时间。 */
private fun Alarm.displayName(): String =
    label.ifBlank { "%02d:%02d".format(hour, minute) }

private fun buildSubtitle(alarm: Alarm): String =
    if (alarm.label.isBlank()) alarm.repeatDays.summary()
    else "${alarm.label} · ${alarm.repeatDays.summary()}"
