package com.example.clock.ui.ring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.clock.alarm.RingingAlarm
import com.example.clock.alarm.SnoozeManager

@Composable
fun RingScreen(
    ringing: RingingAlarm,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 响铃界面固定用深色，不跟随系统主题：无论白天黑夜，锁屏上都要一眼看清。
    val background = Color(0xFF101014)
    val onBackground = Color(0xFFF2F2F7)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 32.dp, vertical = 48.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "%02d:%02d".format(ringing.hour, ringing.minute),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = onBackground,
                textAlign = TextAlign.Center,
            )

            if (ringing.label.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = ringing.label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = onBackground.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(64.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onBackground),
                ) {
                    Text("贪睡 ${SnoozeManager.SNOOZE_MINUTES} 分钟")
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                ) {
                    Text("关闭")
                }
            }
        }
    }
}
