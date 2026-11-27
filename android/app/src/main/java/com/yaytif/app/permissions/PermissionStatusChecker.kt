package com.yaytif.app.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

data class PermissionStatus(
    val notificationListenerGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val postNotificationsGranted: Boolean = true,
    val postNotificationsRequired: Boolean = false
) {
    val corePermissionsGranted: Boolean
        get() = notificationListenerGranted && overlayGranted

    val allRecommendedGranted: Boolean
        get() = corePermissionsGranted && (!postNotificationsRequired || postNotificationsGranted)
}

interface PermissionChecker {
    fun checkStatus(): PermissionStatus
}

object PermissionStatusChecker {

    const val YAYTIF_PACKAGE_NAME = "com.yaytif.app"
    const val ENABLED_NOTIFICATION_LISTENERS_KEY = "enabled_notification_listeners"

    /**
     * Parses the colon-separated component names stored in
     * `Settings.Secure.enabled_notification_listeners` and checks whether any
     * enabled component belongs to [packageName].
     */
    fun isNotificationListenerEnabled(
        enabledListenersSetting: String?,
        packageName: String = YAYTIF_PACKAGE_NAME
    ): Boolean {
        if (enabledListenersSetting.isNullOrBlank() || packageName.isBlank()) {
            return false
        }
        return enabledListenersSetting
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { componentEntry ->
                val slashIndex = componentEntry.indexOf('/')
                if (slashIndex <= 0 || slashIndex == componentEntry.lastIndex) {
                    false
                } else {
                    val componentPackage = componentEntry.substring(0, slashIndex).trim()
                    val componentClass = componentEntry.substring(slashIndex + 1).trim()
                    componentPackage == packageName && componentClass.isNotEmpty()
                }
            }
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledListeners = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                ENABLED_NOTIFICATION_LISTENERS_KEY
            )
        }.getOrNull()
        return isNotificationListenerEnabled(
            enabledListenersSetting = enabledListeners,
            packageName = context.packageName
        )
    }

    fun isOverlayPermissionGranted(context: Context): Boolean {
        return runCatching {
            Settings.canDrawOverlays(context)
        }.getOrDefault(false)
    }

    fun isPostNotificationsRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt >= Build.VERSION_CODES.TIRAMISU
    }

    @SuppressLint("InlinedApi")
    fun isPostNotificationsGranted(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (!isPostNotificationsRequired(sdkInt)) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun buildNotificationListenerSettingsIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun buildOverlayPermissionIntent(packageName: String = YAYTIF_PACKAGE_NAME): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
    }

    fun buildAppNotificationSettingsIntent(packageName: String = YAYTIF_PACKAGE_NAME): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
    }
}

class AndroidPermissionStatusChecker(
    private val context: Context
) : PermissionChecker {

    private val appContext = context.applicationContext

    override fun checkStatus(): PermissionStatus {
        val postRequired = PermissionStatusChecker.isPostNotificationsRequired()
        return PermissionStatus(
            notificationListenerGranted = PermissionStatusChecker.isNotificationListenerEnabled(appContext),
            overlayGranted = PermissionStatusChecker.isOverlayPermissionGranted(appContext),
            postNotificationsGranted = PermissionStatusChecker.isPostNotificationsGranted(appContext),
            postNotificationsRequired = postRequired
        )
    }
}
