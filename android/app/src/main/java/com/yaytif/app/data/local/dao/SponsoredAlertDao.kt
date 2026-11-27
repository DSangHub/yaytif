package com.yaytif.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SponsoredAlertDao {

    @Query(
        """
        SELECT * FROM sponsored_alerts
        WHERE active = 1
        ORDER BY synced_at_epoch_ms DESC, advertiser_name ASC
        """
    )
    fun observeActiveAlerts(): Flow<List<SponsoredAlertEntity>>

    @Query(
        """
        SELECT * FROM sponsored_alerts
        WHERE active = 1 AND category = :category
        ORDER BY synced_at_epoch_ms DESC, advertiser_name ASC
        """
    )
    fun observeActiveAlertsByCategory(category: String): Flow<List<SponsoredAlertEntity>>

    @Query("SELECT * FROM sponsored_alerts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SponsoredAlertEntity?

    @Query("SELECT COUNT(*) FROM sponsored_alerts WHERE active = 1")
    suspend fun countActiveAlerts(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(alerts: List<SponsoredAlertEntity>)

    @Query("DELETE FROM sponsored_alerts WHERE id NOT IN (:activeIds)")
    suspend fun deleteAllExcept(activeIds: List<String>)

    @Query("DELETE FROM sponsored_alerts")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceActiveAlerts(alerts: List<SponsoredAlertEntity>) {
        if (alerts.isEmpty()) {
            clearAll()
        } else {
            upsertAll(alerts)
            deleteAllExcept(alerts.map { it.id })
        }
    }
}
