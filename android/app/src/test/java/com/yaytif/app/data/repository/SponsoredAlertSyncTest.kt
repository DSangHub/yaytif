package com.yaytif.app.data.repository

import com.yaytif.app.data.local.dao.SponsoredAlertDao
import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import com.yaytif.app.data.preferences.UserPreferences
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.YaytifApiFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class SponsoredAlertSyncTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `syncSponsoredAlerts filters inactive alerts, replaces stale cache, and stores timestamp`() = runTest {
        val fakeDao = InMemorySponsoredAlertDao()
        val fakePrefs = InMemoryPreferencesStore()
        val api = YaytifApiFactory.createService(server.url("/").toString())
        val repository = SponsoredAlertRepository(
            sponsoredAlertDao = fakeDao,
            apiService = api,
            preferencesStore = fakePrefs,
            clockMillis = { 1_764_200_000_000L }
        )

        fakeDao.upsertAll(
            listOf(
                SponsoredAlertEntity(
                    id = "stale-alert",
                    advertiserName = "Old Co",
                    title = "Expired",
                    body = "Should be removed",
                    category = "ecommerce",
                    countryCodes = listOf("BR"),
                    payoutPerMinute = "0.100000",
                    affiliateUrl = "https://example.com/{click_id}",
                    commissionShare = "0.5000",
                    budgetRemaining = "0.000000",
                    active = true,
                    createdAtIso = "2026-11-01T00:00:00Z",
                    syncedAtEpochMs = 1L
                )
            )
        )

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "alerts": [
                    {
                      "id": "active-alert-1",
                      "advertiserName": "iFood Brasil",
                      "title": "R$ 25 Off",
                      "body": "Cashback via Pix",
                      "category": "food_delivery",
                      "countryCodes": ["BR"],
                      "priorityColor": "#10B981",
                      "payoutPerMinute": "0.180000",
                      "affiliateUrl": "https://track.yaytif.com/c/ifood-br?click_id={click_id}",
                      "commissionShare": "0.5000",
                      "budgetRemaining": "25000.000000",
                      "active": true,
                      "createdAt": "2026-11-26T12:00:00Z"
                    },
                    {
                      "id": "inactive-alert-2",
                      "advertiserName": "Paused Co",
                      "title": "Paused",
                      "body": "Should not be cached",
                      "category": "ecommerce",
                      "countryCodes": ["BR"],
                      "priorityColor": "#10B981",
                      "payoutPerMinute": "0.050000",
                      "affiliateUrl": "https://track.yaytif.com/c/paused?click_id={click_id}",
                      "commissionShare": "0.5000",
                      "budgetRemaining": "0.000000",
                      "active": false,
                      "createdAt": "2026-11-26T12:00:00Z"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val syncedCount = repository.syncSponsoredAlerts(userId = "u1", countryCode = "BR", category = null)
        assertEquals(1, syncedCount)

        val cached = repository.observeActiveAlerts().first()
        assertEquals(1, cached.size)
        assertEquals("active-alert-1", cached.first().id)
        assertEquals(BigDecimal("0.180000"), cached.first().payoutPerMinuteDecimal())
        assertEquals(1_764_200_000_000L, fakePrefs.getSnapshot().lastAlertSyncEpochMs)
    }

    private class InMemorySponsoredAlertDao : SponsoredAlertDao {
        private val items = MutableStateFlow<Map<String, SponsoredAlertEntity>>(emptyMap())

        override fun observeActiveAlerts(): Flow<List<SponsoredAlertEntity>> =
            items.map { map -> map.values.filter { it.active } }

        override fun observeActiveAlertsByCategory(category: String): Flow<List<SponsoredAlertEntity>> =
            items.map { map -> map.values.filter { it.active && it.category == category } }

        override suspend fun getById(id: String): SponsoredAlertEntity? = items.value[id]

        override suspend fun countActiveAlerts(): Int = items.value.values.count { it.active }

        override suspend fun upsertAll(alerts: List<SponsoredAlertEntity>) {
            val updated = items.value.toMutableMap()
            alerts.forEach { updated[it.id] = it }
            items.value = updated
        }

        override suspend fun deleteAllExcept(activeIds: List<String>) {
            val keep = activeIds.toSet()
            items.value = items.value.filterKeys { it in keep }
        }

        override suspend fun clearAll() {
            items.value = emptyMap()
        }
    }

    private class InMemoryPreferencesStore : UserPreferencesStore {
        private val state = MutableStateFlow(UserPreferences())
        override val preferencesFlow: Flow<UserPreferences> = state
        override suspend fun getSnapshot(): UserPreferences = state.value
        override suspend fun setUserId(userId: String) {
            state.value = state.value.copy(userId = userId)
        }
        override suspend fun setMarket(countryCode: String, currency: String) {
            state.value = state.value.copy(countryCode = countryCode, currency = currency)
        }
        override suspend fun setLocaleTag(localeTag: String) {
            state.value = state.value.copy(localeTag = localeTag)
        }
        override suspend fun setInterests(interests: Set<String>) {
            state.value = state.value.copy(interests = interests)
        }
        override suspend fun setSelectedCategory(category: String?) {
            state.value = state.value.copy(selectedCategory = category)
        }
        override suspend fun setLastAlertSyncEpochMs(epochMs: Long) {
            state.value = state.value.copy(lastAlertSyncEpochMs = epochMs)
        }
    }
}
