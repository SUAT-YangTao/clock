package com.example.clock.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun AlarmListScreen(
    onAddAlarm: () -> Unit,
    onEditAlarm: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Task 15 替换为真正的列表
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("闹钟列表")
    }
}
