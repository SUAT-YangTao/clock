package com.example.clock.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 是否需要请求通知权限。
 *
 * POST_NOTIFICATIONS 是 Android 13 引入的运行时权限，也是本 app 在目标设备
 * （Android 12/13）上唯一需要运行时请求的权限 —— 精确闹钟权限在 12/13 上是
 * 系统自动授予的。
 */
fun shouldRequestNotificationPermission(
    sdkInt: Int,
    hasPermission: Boolean,
    alreadyAsked: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !hasPermission && !alreadyAsked

fun hasNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

/**
 * 首次进入时请求一次通知权限。
 *
 * **作用范围是一次会话。** `alreadyAsked` 是 `rememberSaveable`，能扛住配置变更与
 * 带状态的进程重启，但扛不住一次全新的冷启动 —— 用户拒绝之后从桌面重新打开 app
 * 会再问一次。这是刻意的，不做持久化：系统在两次拒绝后就不再弹窗，最坏情况只是
 * 多问一次；而在一个 composable gate 里引入 SharedPreferences 读写会多出第二个
 * 真相来源，且这段行为在 JVM 上测不了，代价不划算。所以这里不承诺"永不再问"。
 *
 * 用户拒绝后改为提示一次影响：闹钟仍会响（由全屏界面负责），只是通知栏上的快捷
 * 关闭入口不可见。
 *
 * 这是 Review Focus 第 4 条的落点：拒绝权限的路径必须有明确的用户可见反馈，
 * 否则用户遇到"铃声没有关闭入口"时会完全不知道为什么。
 */
@Composable
fun NotificationPermissionGate() {
    val context = LocalContext.current
    var alreadyAsked by rememberSaveable { mutableStateOf(false) }
    var showDeniedNotice by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showDeniedNotice = true
    }

    LaunchedEffect(Unit) {
        if (shouldRequestNotificationPermission(
                sdkInt = Build.VERSION.SDK_INT,
                hasPermission = hasNotificationPermission(context),
                alreadyAsked = alreadyAsked,
            )
        ) {
            alreadyAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showDeniedNotice) {
        AlertDialog(
            onDismissRequest = { showDeniedNotice = false },
            title = { Text("通知权限未开启") },
            text = {
                Text(
                    "闹钟到点仍会全屏响铃，但通知栏上不会显示，也就少了一个快速关闭的入口。" +
                        "如果想让闹钟更可靠，可以到「设置 → 应用 → 闹钟 → 通知」里手动开启。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeniedNotice = false }) { Text("知道了") }
            },
        )
    }
}
