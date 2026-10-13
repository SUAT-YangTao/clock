package com.example.clock.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {

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
}
