package com.yaytif.app.data.repository

import com.yaytif.app.data.local.dao.LocalNotificationDao
import com.yaytif.app.data.local.entity.LocalNotificationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Strictly on-device repository for personal intercepted notifications.
 *
 * PRIVACY INVARIANT (.cursorrules):
 * This repository has zero network dependencies and only reads/writes the local Room DB.
 */
class LocalNotificationRepository(
    private val localNotificationDao: LocalNotificationDao
) {

    fun observeActiveNotifications(): Flow<List<LocalNotificationEntity>> {
        return localNotificationDao.observeActiveNotifications()
    }

    fun observeUnreadCount(): Flow<Int> {
        return localNotificationDao.observeUnreadCount()
    }

    suspend fun saveInterceptedNotification(notification: LocalNotificationEntity): Long {
        return localNotificationDao.upsert(notification)
    }

    suspend fun markAsRead(id: Long) {
        localNotificationDao.markAsRead(id)
    }

    suspend fun markAllAsRead() {
        localNotificationDao.markAllAsRead()
    }

    suspend fun archive(id: Long) {
        localNotificationDao.archive(id)
    }

    suspend fun clearAll() {
        localNotificationDao.clearAll()
    }
}
