package com.example.clock.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class NotificationPermissionTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `Android 12 及以下不需要运行时请求`() {
        for (sdk in 26..32) {
            assertFalse(
                "sdk=$sdk 不应请求通知权限",
                shouldRequestNotificationPermission(sdkInt = sdk, hasPermission = false, alreadyAsked = false),
            )
        }
    }

    @Test
    fun `Android 13 且未授权且未问过 才需要请求`() {
        assertTrue(
            shouldRequestNotificationPermission(sdkInt = 33, hasPermission = false, alreadyAsked = false),
        )
    }

    @Test
    fun `Android 13 已授权则不请求`() {
        assertFalse(
            shouldRequestNotificationPermission(sdkInt = 33, hasPermission = true, alreadyAsked = false),
        )
    }

    @Test
    fun `已经问过就不再重复弹窗`() {
        assertFalse(
            "用户拒绝后不应反复骚扰",
            shouldRequestNotificationPermission(sdkInt = 33, hasPermission = false, alreadyAsked = true),
        )
    }

    @Test
    fun `Android 14 及以上同样需要请求`() {
        assertTrue(
            shouldRequestNotificationPermission(sdkInt = 34, hasPermission = false, alreadyAsked = false),
        )
        assertTrue(
            shouldRequestNotificationPermission(sdkInt = 35, hasPermission = false, alreadyAsked = false),
        )
    }

    /**
     * API 26 上根本没有 POST_NOTIFICATIONS 这个权限，`hasNotificationPermission` 必须
     * 在版本判断处直接返回 true。
     *
     * 这里刻意把通知整个关掉，让"万一走到查询"时得到的一定是拒绝 —— 只有版本判断
     * 短路才可能返回 true。注意 `denyPermissions` 在这个用例里没用：33 以下
     * `ContextCompat.checkSelfPermission` 对 POST_NOTIFICATIONS 根本不查权限，
     * 而是映射到 `NotificationManagerCompat.areNotificationsEnabled()`（见
     * androidx.core 1.15.0 的字节码：`SDK_INT < 33 && equals("...POST_NOTIFICATIONS")`
     * → `areNotificationsEnabled()`），所以真正决定结果的是通知是否被启用。
     */
    @Test
    @Config(sdk = [26])
    fun `Android 8 没有通知权限 一律视为已授权`() {
        val manager = application.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)

        assertTrue(
            "API 26 上不应去查询 POST_NOTIFICATIONS，应直接视为已授权",
            hasNotificationPermission(application),
        )
    }

    @Test
    @Config(sdk = [33])
    fun `Android 13 权限被拒绝则返回 false`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(hasNotificationPermission(application))
    }

    @Test
    @Config(sdk = [33])
    fun `Android 13 权限已授予则返回 true`() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(hasNotificationPermission(application))
    }
}
