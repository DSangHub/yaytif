package com.yaytif.app.ui.state

import com.yaytif.app.data.local.dao.LocalNotificationDao
import com.yaytif.app.data.local.dao.SponsoredAlertDao
import com.yaytif.app.data.local.entity.LocalNotificationEntity
import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import com.yaytif.app.data.preferences.UserPreferences
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.YaytifApiFactory
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.data.repository.LocalNotificationRepository
import com.yaytif.app.data.repository.SponsoredAlertRepository
import com.yaytif.app.data.repository.WalletRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class YaytifViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `submitPayout enforces decimal validation and updates BigDecimal balance on success`() = runBlocking {
        val prefs = FakePreferencesStore(
            UserPreferences(
                userId = "user-br-1",
                countryCode = "BR",
                localeTag = "pt-BR",
                currency = "BRL"
            )
        )
        val localDao = FakeLocalNotificationDao()
        val alertDao = FakeSponsoredAlertDao()
        val api = YaytifApiFactory.createService(server.url("/").toString())

        val vm = YaytifViewModel(
            preferencesStore = prefs,
            sponsoredAlertRepository = SponsoredAlertRepository(alertDao, api, prefs),
            localNotificationRepository = LocalNotificationRepository(localDao),
            walletRepository = WalletRepository(api, prefs),
            enqueueImmediateSync = { _, _ -> }
        )

        // Activate preferences StateFlow so vm.preferences.value reflects stored user
        val collectJob = launch(mainDispatcher) { vm.preferences.collect {} }

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "userId": "user-br-1",
                  "currency": "BRL",
                  "walletBalance": "25.500000",
                  "dailyTimeEarned": "3.200000",
                  "entries": []
                }
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setBody("""{"payouts":[]}"""))

        vm.refreshWallet()
        val loadedState = withTimeout(5_000L) {
            vm.walletState.first { !it.isLoading && it.walletBalance.compareTo(BigDecimal.ZERO) > 0 }
        }
        assertEquals(BigDecimal("25.500000"), loadedState.walletBalance)

        // Blank destination rejected synchronously before network call
        vm.submitPayout(PayoutRailDto.PIX, "   ", "10.00")
        assertEquals(PayoutFeedback.MissingDestination, vm.walletState.value.payoutFeedback)

        // Below BR minimum (R$ 5.00) rejected synchronously
        vm.submitPayout(PayoutRailDto.PIX, "pix@yaytif.com", "2.00")
        assertTrue(vm.walletState.value.payoutFeedback is PayoutFeedback.BelowMinimum)

        // Exceeds available balance rejected synchronously
        vm.submitPayout(PayoutRailDto.PIX, "pix@yaytif.com", "50.00")
        assertEquals(PayoutFeedback.InsufficientBalance, vm.walletState.value.payoutFeedback)

        // Valid payout calls POST /api/v1/payouts and subtracts exact BigDecimal amount
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id": "pay-br-1",
                  "userId": "user-br-1",
                  "rail": "PIX",
                  "destination": "pix@yaytif.com",
                  "amount": "10.250000",
                  "currency": "BRL",
                  "status": "PENDING",
                  "createdAt": "2026-11-26T18:00:00Z"
                }
                """.trimIndent()
            )
        )
        vm.submitPayout(PayoutRailDto.PIX, "pix@yaytif.com", "10.25")
        val submittedState = withTimeout(5_000L) {
            vm.walletState.first { it.payoutFeedback == PayoutFeedback.Submitted }
        }

        assertEquals(0, BigDecimal("15.25").compareTo(submittedState.walletBalance))
        assertEquals(1, submittedState.payouts.size)

        collectJob.cancel()
    }

    private class FakePreferencesStore(initial: UserPreferences) : UserPreferencesStore {
        private val state = MutableStateFlow(initial)
        override val preferencesFlow: Flow<UserPreferences> = state
        override suspend fun getSnapshot(): UserPreferences = state.value
        override suspend fun setUserId(userId: String) { state.value = state.value.copy(userId = userId) }
        override suspend fun setMarket(countryCode: String, currency: String) {
            state.value = state.value.copy(countryCode = countryCode, currency = currency)
        }
        override suspend fun setLocaleTag(localeTag: String) { state.value = state.value.copy(localeTag = localeTag) }
        override suspend fun setInterests(interests: Set<String>) { state.value = state.value.copy(interests = interests) }
        override suspend fun setSelectedCategory(category: String?) { state.value = state.value.copy(selectedCategory = category) }
        override suspend fun setLastAlertSyncEpochMs(epochMs: Long) { state.value = state.value.copy(lastAlertSyncEpochMs = epochMs) }
    }

    private class FakeLocalNotificationDao : LocalNotificationDao {
        private val rows = MutableStateFlow<List<LocalNotificationEntity>>(emptyList())
        override fun observeActiveNotifications(): Flow<List<LocalNotificationEntity>> =
            rows.map { list -> list.filterNot { it.isArchived } }
        override fun observeUnreadCount(): Flow<Int> =
            rows.map { list -> list.count { !it.isArchived && !it.isRead } }
        override suspend fun getById(id: Long): LocalNotificationEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun upsert(notification: LocalNotificationEntity): Long {
            rows.value = rows.value + notification
            return notification.id
        }
        override suspend fun markAsRead(id: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(isRead = true) else it }
        }
        override suspend fun markAllAsRead() {
            rows.value = rows.value.map { it.copy(isRead = true) }
        }
        override suspend fun archive(id: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(isArchived = true, isRead = true) else it }
        }
        override suspend fun deleteById(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }
        override suspend fun deleteOlderThan(cutoffEpochMs: Long): Int = 0
        override suspend fun clearAll() { rows.value = emptyList() }
    }

    private class FakeSponsoredAlertDao : SponsoredAlertDao {
        private val rows = MutableStateFlow<List<SponsoredAlertEntity>>(emptyList())
        override fun observeActiveAlerts(): Flow<List<SponsoredAlertEntity>> = rows
        override fun observeActiveAlertsByCategory(category: String): Flow<List<SponsoredAlertEntity>> =
            rows.map { list -> list.filter { it.category == category } }
        override suspend fun getById(id: String): SponsoredAlertEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun countActiveAlerts(): Int = rows.value.size
        override suspend fun upsertAll(alerts: List<SponsoredAlertEntity>) { rows.value = alerts }
        override suspend fun deleteAllExcept(activeIds: List<String>) {
            rows.value = rows.value.filter { it.id in activeIds }
        }
        override suspend fun clearAll() { rows.value = emptyList() }
    }
}
