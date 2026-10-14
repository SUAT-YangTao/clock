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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 是否需要请求通知权限。
 *
 * POST_NOTIFICATIONS 是 Android 13 引入的运行时权限，也是本 app 在目标设备
 * （Android 12/13）上唯一需要运行时请求的权限。精确闹钟权限虽由系统授予，
 * 但不在本函数的职责内：Android 12/12L 上它可被用户撤销（撤销后调度器会
 * 跳过注册并记 ERROR 日志，见 AlarmScheduler.schedule()）。
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

private const val PREFS_NAME = "notification_permission"
private const val KEY_ASKED = "asked"

/**
 * 是否已经为通知权限问过用户。存在 SharedPreferences 里，而不是 Compose 状态里 ——
 * 这个值必须跨冷启动活着，否则每次重新打开 app 都会再问一遍（见 [NotificationPermissionGate]）。
 */
fun notificationPermissionAsked(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ASKED, false)

fun markNotificationPermissionAsked(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_ASKED, true).apply()
}

/**
 * 首次进入时请求一次通知权限 —— **真的只问一次**。
 *
 * "已经问过"记在 SharedPreferences 里（[notificationPermissionAsked] /
 * [markNotificationPermissionAsked]），不是 `rememberSaveable`：后者只活在当前
 * Activity 的保存态里，冷启动就归零，于是每次重新打开 app 都会再问一遍。
 *
 * 这一点在 Android 13+ 上尤其要紧：用户两次拒绝之后系统不再显示对话框，**但仍会以
 * `granted = false` 回调**。如果"问过没有"不持久化，那个解释用的对话框就会在每次
 * 冷启动时重来一次，没有上限 —— 正是"反复骚扰"本身。
 *
 * 所以：拒绝之后，说明对话框只在拒绝发生的那一次会话里出现一次。之后想开启，
 * 走「设置 → 应用 → 闹钟 → 通知」。
 *
 * 这是 Review Focus 第 4 条的落点：拒绝权限的路径必须有明确的用户可见反馈，
 * 否则用户遇到"铃声没有关闭入口"时会完全不知道为什么。
 */
@Composable
fun NotificationPermissionGate() {
    val context = LocalContext.current
    var showDeniedNotice by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showDeniedNotice = true
    }

    LaunchedEffect(Unit) {
        if (shouldRequestNotificationPermission(
                sdkInt = Build.VERSION.SDK_INT,
                hasPermission = hasNotificationPermission(context),
                alreadyAsked = notificationPermissionAsked(context),
            )
        ) {
            // 先记账再请求：请求进行到一半进程被杀掉，也不会重问。
            markNotificationPermissionAsked(context)
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
