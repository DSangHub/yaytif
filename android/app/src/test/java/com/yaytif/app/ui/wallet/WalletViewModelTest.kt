package com.yaytif.app.ui.wallet

import com.yaytif.app.data.preferences.UserPreferences
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.YaytifApiFactory
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.data.repository.WalletRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: WalletRepository
    private lateinit var fakePrefs: FakeUserPreferencesStore

    private val fixedNow: Instant = Instant.parse("2026-11-26T20:15:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        val api = YaytifApiFactory.createService(server.url("/").toString())
        fakePrefs = FakeUserPreferencesStore(
            UserPreferences(
                userId = "user-br-1",
                countryCode = "BR",
                localeTag = "pt-BR",
                currency = "BRL"
            )
        )
        repository = WalletRepository(api, fakePrefs)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load populates balance, daily cap progress, reset countdown, and paginates ledger without duplicates`() =
        runBlocking {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/api/v1/wallet/user-br-1/ledger") && path.contains("cursor=e2") -> {
                            ledgerResponse(
                                balance = "40.000000",
                                dailyEarned = "5.000000",
                                resetAt = "2026-11-27T00:00:00Z",
                                entriesJson = """
                                    [
                                      {"id":"e2","userId":"user-br-1","type":"AFFILIATE_CASHBACK","amount":"2.000000","currency":"BRL","referenceId":"c1","createdAt":"2026-11-26T17:00:00Z"},
                                      {"id":"e3","userId":"user-br-1","type":"REFUND","amount":"1.500000","currency":"BRL","referenceId":"r1","createdAt":"2026-11-26T16:00:00Z"}
                                    ]
                                """.trimIndent(),
                                hasMore = false,
                                nextCursor = null
                            )
                        }

                        path.startsWith("/api/v1/wallet/user-br-1/ledger") -> {
                            ledgerResponse(
                                balance = "40.000000",
                                dailyEarned = "5.000000",
                                resetAt = "2026-11-27T00:00:00Z",
                                entriesJson = """
                                    [
                                      {"id":"e1","userId":"user-br-1","type":"ATTENTION_TIME","amount":"3.000000","currency":"BRL","referenceId":"s1","createdAt":"2026-11-26T18:00:00Z"},
                                      {"id":"e2","userId":"user-br-1","type":"AFFILIATE_CASHBACK","amount":"2.000000","currency":"BRL","referenceId":"c1","createdAt":"2026-11-26T17:00:00Z"}
                                    ]
                                """.trimIndent(),
                                hasMore = true,
                                nextCursor = "e2"
                            )
                        }

                        path.startsWith("/api/v1/payouts") && request.method == "GET" -> {
                            MockResponse().setBody("""{"payouts":[],"count":0}""")
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val vm = WalletViewModel(
                walletRepository = repository,
                preferencesStore = fakePrefs,
                clock = { fixedNow },
                ledgerPageSize = 2
            )

            val loaded = awaitState(vm) { !it.isRefreshing && it.ledgerEntries.size == 2 }
            assertEquals(BigDecimal("40.000000"), loaded.walletBalance)
            assertEquals(BigDecimal("5.000000"), loaded.dailyTimeEarned)
            // BRL daily cap = 10.00 -> 5.00 / 10.00 = 0.5000 (50%)
            assertEquals(BigDecimal("0.5000"), loaded.dailyCapProgress.fraction)
            assertEquals(50, loaded.dailyCapProgress.percent)
            assertFalse(loaded.dailyCapProgress.isCapReached)
            // 20:15 -> 00:00 = 3h 45m
            assertEquals(ResetCountdown(hours = 3L, minutes = 45L, isResettingSoon = false), loaded.resetCountdown)
            assertTrue(loaded.hasMoreLedger)
            assertEquals("e2", loaded.nextLedgerCursor)

            vm.loadMoreLedger()
            val afterPage2 = awaitState(vm) { !it.isLoadingMoreLedger && !it.hasMoreLedger }
            assertEquals(listOf("e1", "e2", "e3"), afterPage2.ledgerEntries.map { it.id })
            assertNull(afterPage2.nextLedgerCursor)
        }

    @Test
    fun `withdrawal flow validates, confirms, submits once under double-tap, and refreshes wallet`() =
        runBlocking {
            val postCount = AtomicInteger(0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val submitted = postCount.get() > 0
                    return when {
                        request.method == "POST" && path == "/api/v1/payouts" -> {
                            postCount.incrementAndGet()
                            MockResponse()
                                .setResponseCode(201)
                                .setBodyDelay(50, TimeUnit.MILLISECONDS)
                                .setBody(
                                    """
                                    {
                                      "payout": {
                                        "id": "pay-new-1",
                                        "userId": "user-br-1",
                                        "rail": "PIX",
                                        "destination": "12345678901",
                                        "amount": "25.000000",
                                        "currency": "BRL",
                                        "status": "PENDING",
                                        "createdAt": "2026-11-26T20:15:00Z"
                                      },
                                      "ledgerEntryId": "led-w-1",
                                      "walletBalance": "25.000000"
                                    }
                                    """.trimIndent()
                                )
                        }

                        request.method == "GET" && path.startsWith("/api/v1/wallet/user-br-1/ledger") -> {
                            if (!submitted) {
                                ledgerResponse(balance = "50.000000", dailyEarned = "5.000000")
                            } else {
                                ledgerResponse(
                                    balance = "25.000000",
                                    dailyEarned = "5.000000",
                                    entriesJson = """
                                        [
                                          {"id":"led-w-1","userId":"user-br-1","type":"PAYOUT_WITHDRAWAL","amount":"-25.000000","currency":"BRL","referenceId":"pay-new-1","createdAt":"2026-11-26T20:15:00Z"}
                                        ]
                                    """.trimIndent()
                                )
                            }
                        }

                        request.method == "GET" && path.startsWith("/api/v1/payouts") -> {
                            if (!submitted) {
                                MockResponse().setBody("""{"payouts":[],"count":0}""")
                            } else {
                                MockResponse().setBody(
                                    """
                                    {
                                      "payouts": [
                                        {
                                          "id": "pay-new-1",
                                          "userId": "user-br-1",
                                          "rail": "PIX",
                                          "destination": "12345678901",
                                          "amount": "25.000000",
                                          "currency": "BRL",
                                          "status": "PENDING",
                                          "createdAt": "2026-11-26T20:15:00Z"
                                        }
                                      ],
                                      "count": 1
                                    }
                                    """.trimIndent()
                                )
                            }
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val vm = WalletViewModel(
                walletRepository = repository,
                preferencesStore = fakePrefs,
                clock = { fixedNow }
            )
            awaitState(vm) { !it.isRefreshing && it.walletBalance == BigDecimal("50.000000") }

            // Select Pix (default for BR), apply 50% quick amount (= 25.00), enter valid CPF
            vm.onDestinationChange("123.456.789-01")
            vm.applyQuickAmount(50)
            assertEquals("25.00", vm.uiState.value.amountInput)

            vm.requestConfirmation()
            val pending = vm.uiState.value.pendingConfirmation
            assertNotNull(pending)
            assertEquals(PayoutRailDto.PIX, pending!!.rail)
            assertEquals("12345678901", pending.normalizedDestination)
            assertEquals(BigDecimal("25.00"), pending.amount)

            // Double-submit attempt: only ONE POST should ever hit the server
            vm.confirmAndSubmitPayout()
            vm.confirmAndSubmitPayout()

            val finalState = awaitState(vm) {
                !it.isSubmittingPayout && !it.isRefreshing &&
                    it.feedback == WalletFeedback.PayoutSubmitted &&
                    it.ledgerEntries.any { e -> e.id == "led-w-1" }
            }

            assertEquals(1, postCount.get())
            assertEquals(WalletFeedback.PayoutSubmitted, finalState.feedback)
            assertEquals(BigDecimal("25.000000"), finalState.walletBalance)
            assertEquals("", finalState.destinationInput)
            assertEquals("", finalState.amountInput)
            assertEquals(1, finalState.payouts.size)
            assertEquals("pay-new-1", finalState.payouts.first().id)
            assertFalse(finalState.isSubmittingPayout)
        }

    @Test
    fun `HTTP 409 conflict surfaces InsufficientBalanceConflict feedback`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.startsWith("/api/v1/wallet/user-br-1/ledger") ->
                        ledgerResponse(balance = "50.000000", dailyEarned = "5.000000")
                    request.method == "GET" && path.startsWith("/api/v1/payouts") ->
                        MockResponse().setBody("""{"payouts":[],"count":0}""")
                    request.method == "POST" && path == "/api/v1/payouts" ->
                        MockResponse().setResponseCode(409).setBody("""{"error":"INSUFFICIENT_BALANCE"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val vm = WalletViewModel(
            walletRepository = repository,
            preferencesStore = fakePrefs,
            clock = { fixedNow }
        )
        awaitState(vm) { !it.isRefreshing && it.walletBalance == BigDecimal("50.000000") }

        vm.onDestinationChange("12345678901")
        vm.onAmountChange("30.00")
        vm.requestConfirmation()
        assertNotNull(vm.uiState.value.pendingConfirmation)

        vm.confirmAndSubmitPayout()
        val afterError = awaitState(vm) {
            !it.isSubmittingPayout && it.feedback == WalletFeedback.InsufficientBalanceConflict
        }

        assertEquals(WalletFeedback.InsufficientBalanceConflict, afterError.feedback)
        assertFalse(afterError.isSubmittingPayout)
    }

    @Test
    fun `computeDailyCapProgress clamps cap overflow and computes accurate BigDecimal fraction`() {
        val half = WalletViewModel.computeDailyCapProgress(
            earned = BigDecimal("5.00"),
            capLimit = BigDecimal("10.00")
        )
        assertEquals(BigDecimal("0.5000"), half.fraction)
        assertEquals(50, half.percent)
        assertFalse(half.isCapReached)

        val over = WalletViewModel.computeDailyCapProgress(
            earned = BigDecimal("15.00"),
            capLimit = BigDecimal("10.00")
        )
        assertEquals(BigDecimal.ONE, over.fraction)
        assertEquals(100, over.percent)
        assertTrue(over.isCapReached)

        val pastReset = WalletViewModel.computeResetCountdown(
            resetAtIso = "2026-11-26T19:00:00Z",
            now = fixedNow
        )
        assertEquals(ResetCountdown(hours = 0L, minutes = 0L, isResettingSoon = true), pastReset)
    }

    private suspend fun awaitState(
        vm: WalletViewModel,
        predicate: (WalletUiState) -> Boolean
    ): WalletUiState = withTimeout(5_000L) {
        vm.uiState.first(predicate)
    }

    private fun ledgerResponse(
        balance: String,
        dailyEarned: String,
        resetAt: String = "2026-11-27T00:00:00Z",
        entriesJson: String = "[]",
        hasMore: Boolean = false,
        nextCursor: String? = null
    ): MockResponse {
        val cursorJson = nextCursor?.let { "\"$it\"" } ?: "null"
        return MockResponse().setBody(
            """
            {
              "wallet": {
                "userId": "user-br-1",
                "walletBalance": "$balance",
                "dailyTimeEarned": "$dailyEarned",
                "dailyCapResetAt": "$resetAt",
                "currency": "BRL"
              },
              "entries": $entriesJson,
              "pagination": {
                "limit": 20,
                "hasMore": $hasMore,
                "nextCursor": $cursorJson
              }
            }
            """.trimIndent()
        )
    }
}

private class FakeUserPreferencesStore(
    initial: UserPreferences
) : UserPreferencesStore {
    private val state = MutableStateFlow(initial)
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
