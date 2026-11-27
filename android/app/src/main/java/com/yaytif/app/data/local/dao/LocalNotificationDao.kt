package com.yaytif.app.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yaytif.app.data.local.entity.LocalNotificationEntity
import kotlinx.coroutines.flow.Flow

data class VaultAppSummary(
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "app_label")
    val appLabel: String
)

/**
 * Local-only DAO for personal intercepted notifications.
 * Never exposed to network repositories or background upload workers.
 */
@Dao
interface LocalNotificationDao {

    @Query(
        """
        SELECT * FROM local_notifications
        WHERE is_archived = 0
        ORDER BY received_at_epoch_ms DESC
        """
    )
    fun observeActiveNotifications(): Flow<List<LocalNotificationEntity>>

    @Query(
        """
        SELECT * FROM local_notifications
        WHERE is_archived = 0
          AND (:unreadOnly = 0 OR is_read = 0)
          AND (:packageName IS NULL OR package_name = :packageName)
          AND (
            :query = ''
            OR LOWER(title) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(body) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(app_label) LIKE '%' || LOWER(:query) || '%'
            OR (sender_name IS NOT NULL AND LOWER(sender_name) LIKE '%' || LOWER(:query) || '%')
          )
        ORDER BY received_at_epoch_ms DESC
        """
    )
    fun observeFilteredNotifications(
        query: String,
        unreadOnly: Boolean,
        packageName: String?
    ): Flow<List<LocalNotificationEntity>>

    @Query(
        """
        SELECT DISTINCT package_name, app_label
        FROM local_notifications
        WHERE is_archived = 0
        ORDER BY app_label ASC
        """
    )
    fun observeDistinctApps(): Flow<List<VaultAppSummary>>

    @Query(
        """
        SELECT COUNT(*) FROM local_notifications
        WHERE is_archived = 0 AND is_read = 0
        """
    )
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM local_notifications WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LocalNotificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notification: LocalNotificationEntity): Long

    @Query("UPDATE local_notifications SET is_read = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    @Query("UPDATE local_notifications SET is_read = 0 WHERE id = :id")
    suspend fun markAsUnread(id: Long)

    @Query("UPDATE local_notifications SET is_read = 1 WHERE is_archived = 0")
    suspend fun markAllAsRead()

    @Query("UPDATE local_notifications SET is_archived = 1, is_read = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("DELETE FROM local_notifications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM local_notifications WHERE received_at_epoch_ms < :cutoffEpochMs")
    suspend fun deleteOlderThan(cutoffEpochMs: Long): Int

    @Query("DELETE FROM local_notifications")
    suspend fun clearAll()
}
