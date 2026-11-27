package com.yaytif.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yaytif.app.data.local.entity.LocalNotificationEntity
import kotlinx.coroutines.flow.Flow

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
