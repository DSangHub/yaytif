package com.yaytif.app.service

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.yaytif.app.YaytifApplication
import com.yaytif.app.data.local.YaytifDatabase
import com.yaytif.app.data.repository.LocalNotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * On-device NotificationListenerService that captures non-noise personal notifications
 * and persists them strictly inside the local Room database.
 *
 * PRIVACY INVARIANT (.cursorrules):
 * Never imports network clients or logs third-party notification titles, bodies, or sender names.
 */
class YaytifNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val localNotificationRepository: LocalNotificationRepository by lazy {
        val app = application as? YaytifApplication
        app?.appContainer?.localNotificationRepository
            ?: LocalNotificationRepository(
                YaytifDatabase.getInstance(applicationContext).localNotificationDao()
            )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusBarNotification = sbn ?: return
        val payload = toRawPayload(statusBarNotification)
        val entity = NotificationExtractor.extract(
            payload = payload,
            selfPackageName = applicationContext.packageName
        ) ?: return

        serviceScope.launch {
            runCatching {
                localNotificationRepository.saveInterceptedNotification(entity)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun toRawPayload(sbn: StatusBarNotification): RawNotificationPayload {
        val notification = sbn.notification
        val extras = notification?.extras
        val flags = notification?.flags ?: 0

        val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isForegroundService = (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val conversationTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val senderName = extractSenderName(notification)

        val pkg = sbn.packageName.orEmpty()
        val appLabel = resolveAppLabel(pkg)

        return RawNotificationPayload(
            packageName = pkg,
            appLabel = appLabel,
            statusBarKey = sbn.key,
            notificationId = sbn.id,
            tag = sbn.tag,
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            conversationTitle = conversationTitle,
            senderName = senderName,
            category = notification?.category,
            postTimeEpochMs = sbn.postTime,
            isOngoing = isOngoing,
            isForegroundService = isForegroundService,
            isGroupSummary = isGroupSummary
        )
    }

    private fun extractSenderName(notification: Notification?): String? {
        if (notification == null) return null

        val messagingStyle = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()

        val lastMessageSender = messagingStyle?.messages
            ?.lastOrNull()
            ?.person
            ?.name
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        if (lastMessageSender != null) {
            return lastMessageSender
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val extras = notification.extras ?: return null
            val people = runCatching {
                @Suppress("DEPRECATION")
                extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)
            }.getOrNull()
            val personName = people?.firstOrNull()?.name?.toString()?.takeIf { it.isNotBlank() }
            if (personName != null) {
                return personName
            }
        }

        return null
    }

    private fun resolveAppLabel(packageName: String): String? {
        if (packageName.isBlank()) return null
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrNull()
    }
}
