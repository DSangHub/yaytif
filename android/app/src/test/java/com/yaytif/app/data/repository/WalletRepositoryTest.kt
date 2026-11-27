package com.yaytif.app.data.repository

import com.yaytif.app.data.preferences.UserPreferences
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.YaytifApiFactory
import com.yaytif.app.data.remote.dto.LedgerEntryTypeDto
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.data.remote.dto.PayoutStatusDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class WalletRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: WalletRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = YaytifApiFactory.createService(server.url("/").toString())
        val prefs = InMemoryPrefsStore(UserPreferences(userId = "user-br-1", countryCode = "BR", currency = "BRL"))
        repository = WalletRepository(api, prefs)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetchWalletLedger passes cursor and limit and parses wallet summary and pagination`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "wallet": {
                    "userId": "user-br-1",
                    "walletBalance": "42.750000",
                    "dailyTimeEarned": "6.250000",
                    "dailyCapResetAt": "2026-11-27T00:00:00.000Z",
                    "currency": "BRL"
                  },
                  "entries": [
                    {
                      "id": "entry-2",
                      "userId": "user-br-1",
                      "type": "AFFILIATE_CASHBACK",
                      "amount": "12.500000",
                      "currency": "BRL",
                      "referenceId": "click-99",
                      "createdAt": "2026-11-26T15:00:00.000Z"
                    }
                  ],
                  "pagination": {
                    "limit": 15,
                    "hasMore": false,
                    "nextCursor": null
                  }
                }
                """.trimIndent()
            )
        )

        val response = repository.fetchWalletLedger(
            userId = "user-br-1",
            cursor = "entry-1",
            limit = 15
        )

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/wallet/user-br-1/ledger?cursor=entry-1&limit=15", req.path)
        assertEquals(BigDecimal("42.750000"), response.wallet.walletBalance)
        assertEquals(BigDecimal("6.250000"), response.wallet.dailyTimeEarned)
        assertEquals("BRL", response.wallet.currency)
        assertEquals(1, response.entries.size)
        assertEquals(LedgerEntryTypeDto.AFFILIATE_CASHBACK, response.entries.first().type)
        assertFalse(response.pagination.hasMore)
    }

    @Test
    fun `fetchPayouts sends optional filters and parses payout list`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "payouts": [
                    {
                      "id": "pay-10",
                      "userId": "user-ng-1",
                      "rail": "PAYSTACK_OPAY",
                      "destination": "0123456789",
                      "amount": "2500.000000",
                      "currency": "NGN",
                      "status": "PROCESSING",
                      "externalTxId": null,
                      "createdAt": "2026-11-26T14:00:00.000Z"
                    }
                  ],
                  "count": 1
                }
                """.trimIndent()
            )
        )

        val payouts = repository.fetchPayouts(
            userId = "user-ng-1",
            status = PayoutStatusDto.PROCESSING,
            limit = 10
        )

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/payouts?userId=user-ng-1&status=PROCESSING&limit=10", req.path)
        assertEquals(1, payouts.size)
        assertEquals(PayoutRailDto.PAYSTACK_OPAY, payouts.first().rail)
        assertEquals(PayoutStatusDto.PROCESSING, payouts.first().status)
        assertEquals(BigDecimal("2500.000000"), payouts.first().amount)
    }

    @Test
    fun `submitPayout returns Success on 201 and maps 409, 400, 404, and 500 accurately`() = runTest {
        // 1. 201 Created
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {
                  "payout": {
                    "id": "pay-pix-1",
                    "userId": "user-br-1",
                    "rail": "PIX",
                    "destination": "12345678901",
                    "amount": "20.000000",
                    "currency": "BRL",
                    "status": "PENDING",
                    "createdAt": "2026-11-26T16:00:00.000Z"
                  },
                  "ledgerEntryId": "led-w-1",
                  "walletBalance": "22.750000"
                }
                """.trimIndent()
            )
        )
        // 2. 409 Conflict (insufficient balance)
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":"INSUFFICIENT_BALANCE","message":"Insufficient wallet balance for payout."}"""
            )
        )
        // 3. 400 Bad Request
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":"VALIDATION_ERROR","message":"Invalid destination"}"""
            )
        )
        // 4. 404 Not Found
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"error":"USER_NOT_FOUND"}"""
            )
        )
        // 5. 500 Server Error
        server.enqueue(MockResponse().setResponseCode(500))

        val success = repository.submitPayout("user-br-1", PayoutRailDto.PIX, "12345678901", BigDecimal("20.00"))
        assertTrue(success is PayoutSubmissionResult.Success)
        val created = (success as PayoutSubmissionResult.Success).response
        assertEquals("pay-pix-1", created.payout.id)
        assertEquals(BigDecimal("22.750000"), created.walletBalance)

        val conflict = repository.submitPayout("user-br-1", PayoutRailDto.PIX, "12345678901", BigDecimal("200.00"))
        assertEquals(PayoutSubmissionResult.InsufficientFunds, conflict)

        val badRequest = repository.submitPayout("user-br-1", PayoutRailDto.PIX, "bad", BigDecimal("20.00"))
        assertEquals(PayoutSubmissionResult.ValidationError, badRequest)

        val notFound = repository.submitPayout("missing-user", PayoutRailDto.PIX, "12345678901", BigDecimal("20.00"))
        assertEquals(PayoutSubmissionResult.UserNotFound, notFound)

        val serverError = repository.submitPayout("user-br-1", PayoutRailDto.PIX, "12345678901", BigDecimal("20.00"))
        assertTrue(serverError is PayoutSubmissionResult.NetworkError)
    }
}

private class InMemoryPrefsStore(
    initial: UserPreferences
) : UserPreferencesStore {
    private val state = MutableStateFlow(initial)
    override val preferencesFlow: Flow<UserPreferences> = state
    override suspend fun getSnapshot(): UserPreferences = state.value
    override suspend fun setUserId(userId: String) { state.value = state.value.copy(userId = userId) }
    override suspend fun setMarket(countryCode: String, currency: String) {
        state.value = state.value.copy(countryCode = countryCode, currency = currency)
    }
    override suspend fun setLocaleTag(localeTag: String) { state.value = state.value.copy(localeTag = localeTag) }
    override suspend fun setInterests(interests: Set<String>) { state.value = state.value.copy(interests = interests) }
    override suspend fun setSelectedCategory(category: String?) {
        state.value = state.value.copy(selectedCategory = category)
    }
    override suspend fun setLastAlertSyncEpochMs(epochMs: Long) {
        state.value = state.value.copy(lastAlertSyncEpochMs = epochMs)
    }
}
