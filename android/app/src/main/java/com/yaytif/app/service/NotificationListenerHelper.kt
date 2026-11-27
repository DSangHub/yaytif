package com.yaytif.app.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Checks notification listener permission status and constructs the system settings intent.
 */
object NotificationListenerHelper {

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context.applicationContext)
        return enabledPackages.contains(context.packageName)
    }

    fun createNotificationListenerSettingsIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
