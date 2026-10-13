package com.example.clock.ui.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun AlarmEditScreen(
    alarmId: Long?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Task 16 替换为真正的编辑界面
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("编辑闹钟 $alarmId")
    }
}
