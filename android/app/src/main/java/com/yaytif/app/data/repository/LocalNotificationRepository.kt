package com.yaytif.app.data.repository

import com.yaytif.app.data.local.dao.LocalNotificationDao
import com.yaytif.app.data.local.dao.VaultAppSummary
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

    fun observeFilteredNotifications(
        query: String = "",
        unreadOnly: Boolean = false,
        packageName: String? = null
    ): Flow<List<LocalNotificationEntity>> {
        val normalizedQuery = query.trim()
        val normalizedPackage = packageName?.trim()?.takeIf { it.isNotEmpty() }
        return localNotificationDao.observeFilteredNotifications(
            query = normalizedQuery,
            unreadOnly = unreadOnly,
            packageName = normalizedPackage
        )
    }

    fun observeDistinctApps(): Flow<List<VaultAppSummary>> {
        return localNotificationDao.observeDistinctApps()
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

    suspend fun markAsUnread(id: Long) {
        localNotificationDao.markAsUnread(id)
    }

    suspend fun markAllAsRead() {
        localNotificationDao.markAllAsRead()
    }

    suspend fun archive(id: Long) {
        localNotificationDao.archive(id)
    }

    suspend fun deleteById(id: Long) {
        localNotificationDao.deleteById(id)
    }

    suspend fun pruneOlderThanDays(
        retentionDays: Int = DEFAULT_RETENTION_DAYS,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int {
        require(retentionDays > 0) { "retentionDays must be strictly positive" }
        val cutoffEpochMs = (nowEpochMs - retentionDays.toLong() * MILLIS_PER_DAY).coerceAtLeast(0L)
        return localNotificationDao.deleteOlderThan(cutoffEpochMs)
    }

    suspend fun clearAll() {
        localNotificationDao.clearAll()
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
