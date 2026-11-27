package com.yaytif.app.data.repository

import com.yaytif.app.data.local.dao.SponsoredAlertDao
import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.YaytifApiService
import com.yaytif.app.data.remote.dto.SessionHeartbeatRequest
import com.yaytif.app.data.remote.dto.SessionHeartbeatResponse
import com.yaytif.app.data.remote.dto.StartSessionRequest
import com.yaytif.app.data.remote.dto.StartSessionResponse
import kotlinx.coroutines.flow.Flow

class SponsoredAlertRepository(
    private val sponsoredAlertDao: SponsoredAlertDao,
    private val apiService: YaytifApiService,
    private val preferencesStore: UserPreferencesStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {

    fun observeActiveAlerts(category: String? = null): Flow<List<SponsoredAlertEntity>> {
        return if (category.isNullOrBlank()) {
            sponsoredAlertDao.observeActiveAlerts()
        } else {
            sponsoredAlertDao.observeActiveAlertsByCategory(category)
        }
    }

    suspend fun syncSponsoredAlerts(
        userId: String? = null,
        countryCode: String? = null,
        category: String? = null
    ): Int {
        val response = apiService.getAlerts(
            userId = userId,
            countryCode = countryCode,
            category = category
        )
        val syncedAt = clockMillis()
        val entities = response.alerts
            .filter { it.active }
            .map { dto -> dto.toEntity(syncedAtEpochMs = syncedAt) }

        sponsoredAlertDao.replaceActiveAlerts(entities)
        preferencesStore.setLastAlertSyncEpochMs(syncedAt)
        return entities.size
    }

    suspend fun startAdSession(userId: String, alertId: String): StartSessionResponse {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(alertId.isNotBlank()) { "alertId must not be blank" }
        return apiService.startSession(
            StartSessionRequest(userId = userId, alertId = alertId)
        )
    }

    suspend fun sendAttentionHeartbeat(
        sessionId: String,
        activeSecondsDelta: Int
    ): SessionHeartbeatResponse {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(activeSecondsDelta > 0) { "activeSecondsDelta must be strictly positive" }
        return apiService.sendSessionHeartbeat(
            sessionId = sessionId,
            request = SessionHeartbeatRequest(activeSecondsDelta = activeSecondsDelta)
        )
    }
}
