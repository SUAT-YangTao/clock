package com.example.clock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.clock.AppGraph
import kotlinx.coroutines.runBlocking

/**
 * 系统事件触发重算。
 *
 * **四种事件一个都不能少**：任何一个漏接，闹钟都会静默失效且没有任何报错 ——
 * 这是闹钟 app 最难排查的故障模式。
 *
 * manifest 中声明 android:exported="false"。按 <receiver> 的官方文档，
 * exported="false" 的接收器仍能收到系统广播（只是收不到普通应用发来的），
 * 而这四个广播全都是系统发出的受保护广播。
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> reschedule(context)

            else -> Unit
        }
    }

    private fun reschedule(context: Context) {
        runBlocking {
            try {
                AppGraph.scheduler(context).scheduleAll()
            } catch (e: Exception) {
                Log.e(TAG, "系统事件后重算闹钟失败", e)
            }
        }
    }

    private companion object {
        const val TAG = "SystemEventReceiver"
    }
}
