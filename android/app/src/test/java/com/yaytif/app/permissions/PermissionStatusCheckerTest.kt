package com.yaytif.app.permissions

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStatusCheckerTest {

    @Test
    fun isNotificationListenerEnabled_matchesYaytifComponentInColonDelimitedList() {
        val setting = listOf(
            "com.example.other/com.example.other.Listener",
            "com.yaytif.app/com.yaytif.app.service.YaytifNotificationListenerService",
            "com.another.app/.NotificationService"
        ).joinToString(":")

        assertTrue(PermissionStatusChecker.isNotificationListenerEnabled(setting))
    }

    @Test
    fun isNotificationListenerEnabled_matchesShortComponentNotationAndWhitespace() {
        val setting = "  com.yaytif.app/.service.YaytifNotificationListenerService  "
        assertTrue(PermissionStatusChecker.isNotificationListenerEnabled(setting))
    }

    @Test
    fun isNotificationListenerEnabled_rejectsNullBlankUnrelatedAndPrefixSpoofedPackages() {
        assertFalse(PermissionStatusChecker.isNotificationListenerEnabled(null))
        assertFalse(PermissionStatusChecker.isNotificationListenerEnabled(""))
        assertFalse(PermissionStatusChecker.isNotificationListenerEnabled("   "))
        assertFalse(
            PermissionStatusChecker.isNotificationListenerEnabled(
                "com.other.app/com.other.app.Listener"
            )
        )
        assertFalse(
            PermissionStatusChecker.isNotificationListenerEnabled(
                "com.yaytif.app.evil/com.evil.Listener"
            )
        )
        assertFalse(
            PermissionStatusChecker.isNotificationListenerEnabled(
                "com.yaytif.app"
            )
        )
        assertFalse(
            PermissionStatusChecker.isNotificationListenerEnabled(
                "com.yaytif.app/"
            )
        )
    }

    @Test
    fun isPostNotificationsRequired_onlyOnApi33AndAbove() {
        assertFalse(PermissionStatusChecker.isPostNotificationsRequired(Build.VERSION_CODES.O))
        assertFalse(PermissionStatusChecker.isPostNotificationsRequired(Build.VERSION_CODES.S_V2))
        assertTrue(PermissionStatusChecker.isPostNotificationsRequired(Build.VERSION_CODES.TIRAMISU))
        assertTrue(PermissionStatusChecker.isPostNotificationsRequired(35))
    }

    @Test
    fun permissionStatus_computesCoreAndRecommendedGrantFlagsAccurately() {
        val missingBoth = PermissionStatus(
            notificationListenerGranted = false,
            overlayGranted = false,
            postNotificationsGranted = false,
            postNotificationsRequired = true
        )
        assertFalse(missingBoth.corePermissionsGranted)
        assertFalse(missingBoth.allRecommendedGranted)

        val coreOnlyApi33 = PermissionStatus(
            notificationListenerGranted = true,
            overlayGranted = true,
            postNotificationsGranted = false,
            postNotificationsRequired = true
        )
        assertTrue(coreOnlyApi33.corePermissionsGranted)
        assertFalse(coreOnlyApi33.allRecommendedGranted)

        val coreOnlyPreApi33 = PermissionStatus(
            notificationListenerGranted = true,
            overlayGranted = true,
            postNotificationsGranted = false,
            postNotificationsRequired = false
        )
        assertTrue(coreOnlyPreApi33.corePermissionsGranted)
        assertTrue(coreOnlyPreApi33.allRecommendedGranted)

        val allGrantedApi33 = PermissionStatus(
            notificationListenerGranted = true,
            overlayGranted = true,
            postNotificationsGranted = true,
            postNotificationsRequired = true
        )
        assertTrue(allGrantedApi33.corePermissionsGranted)
        assertTrue(allGrantedApi33.allRecommendedGranted)
    }

    @Test
    fun intentBuilders_constructNonNullIntents() {
        assertNotNull(PermissionStatusChecker.buildNotificationListenerSettingsIntent())
        assertNotNull(PermissionStatusChecker.buildOverlayPermissionIntent("com.yaytif.app"))
        assertNotNull(PermissionStatusChecker.buildAppNotificationSettingsIntent("com.yaytif.app"))
    }
}
