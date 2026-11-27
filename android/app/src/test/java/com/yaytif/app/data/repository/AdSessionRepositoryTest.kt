package com.yaytif.app.data.repository

import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import com.yaytif.app.data.remote.YaytifApiFactory
import com.yaytif.app.domain.AttentionStopReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class AdSessionRepositoryTest {

    private lateinit var server: MockWebServer

    private val sampleAlert = SponsoredAlertEntity(
        id = "alert-ifood-br",
        advertiserName = "iFood Brasil",
        title = "R$ 25 Off no iFood",
        body = "Cashback instantâneo via Pix",
        category = "food_delivery",
        countryCodes = listOf("BR"),
        priorityColor = "#10B981",
        payoutPerMinute = "0.600000",
        affiliateUrl = "https://track.yaytif.com/c/ifood-br?click_id={click_id}",
        commissionShare = "0.5000",
        budgetRemaining = "1200.000000",
        active = true,
        createdAtIso = "2026-11-26T12:00:00.000Z",
        syncedAtEpochMs = 1_764_200_000_000L
    )

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
    fun `startSession posts to sessions start and flushes verified heartbeats via StateFlow`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repoScope = TestScope(dispatcher)
        val api = YaytifApiFactory.createService(server.url("/").toString())
        val repository = AdSessionRepository(
            apiService = api,
            scope = repoScope,
            flushIntervalSeconds = 5,
            tickIntervalMs = 1_000L
        )

        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {
                  "sessionId": "sess-001",
                  "clickId": "clk-777",
                  "resolvedAffiliateUrl": "https://track.yaytif.com/c/ifood-br?click_id=clk-777"
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "sessionId": "sess-001",
                  "activeSeconds": 5,
                  "activeSecondsDelta": 5,
                  "creditedDelta": "0.050000",
                  "sessionCreditedTotal": "0.050000",
                  "walletBalance": "10.050000",
                  "dailyTimeEarned": "0.050000",
                  "dailyCap": "2.000000",
                  "dailyCapResetAt": "2026-11-27T00:00:00.000Z",
                  "dailyCapReached": false,
                  "alertBudgetRemaining": "1199.950000",
                  "alertActive": true,
                  "ledgerEntryId": "led-001"
                }
                """.trimIndent()
            )
        )

        val started = repository.startSession(
            userId = "user-br-1",
            alert = sampleAlert,
            currencyCode = "BRL"
        )
        assertTrue(started.isSuccess)

        val startRequest = server.takeRequest()
        assertEquals("POST", startRequest.method)
        assertEquals("/api/v1/sessions/start", startRequest.path)
        val startBody = startRequest.body.readUtf8()
        assertTrue(startBody.contains(""""userId":"user-br-1""""))
        assertTrue(startBody.contains(""""alertId":"alert-ifood-br""""))

        val initial = repository.activeSession.value
        assertNotNull(initial)
        assertEquals("sess-001", initial!!.sessionId)
        assertEquals("clk-777", initial.clickId)
        assertEquals("https://track.yaytif.com/c/ifood-br?click_id=clk-777", initial.resolvedAffiliateUrl)
        assertTrue(initial.isAffiliateUrlValid)

        // Advance 5 seconds -> triggers automatic heartbeat flush
        repoScope.advanceTimeBy(5_000L)
        repoScope.runCurrent()

        val heartbeatReq = server.takeRequest()
        assertEquals("POST", heartbeatReq.method)
        assertEquals("/api/v1/sessions/sess-001/heartbeat", heartbeatReq.path)
        assertTrue(heartbeatReq.body.readUtf8().contains(""""activeSecondsDelta":5"""))

        val updated = repoScope.awaitState(repository) {
            it != null && it.timerState.verifiedActiveSeconds == 5 && !it.timerState.isFlushing
        }
        assertNotNull(updated)
        assertEquals(5, updated!!.timerState.verifiedActiveSeconds)
        assertEquals(BigDecimal("0.050000"), updated.timerState.confirmedEarnedAmount)
        assertEquals(BigDecimal("10.050000"), updated.timerState.walletBalance)

        // End session cleanly
        repository.endSession()
        assertNull(repository.activeSession.value)
    }

    @Test
    fun `retries HTTP 500 heartbeat without double-counting seconds and stops on daily cap`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repoScope = TestScope(dispatcher)
        val api = YaytifApiFactory.createService(server.url("/").toString())
        val repository = AdSessionRepository(
            apiService = api,
            scope = repoScope,
            flushIntervalSeconds = 5,
            tickIntervalMs = 1_000L
        )

        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {
                  "sessionId": "sess-retry-1",
                  "clickId": "clk-888",
                  "resolvedAffiliateUrl": "https://track.yaytif.com/c/ifood-br?click_id=clk-888"
                }
                """.trimIndent()
            )
        )
        // First heartbeat fails with 500
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"temporary"}"""))
        // Second heartbeat succeeds and signals daily cap reached
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "sessionId": "sess-retry-1",
                  "activeSeconds": 6,
                  "activeSecondsDelta": 6,
                  "creditedDelta": "0.060000",
                  "sessionCreditedTotal": "0.060000",
                  "walletBalance": "2.000000",
                  "dailyTimeEarned": "2.000000",
                  "dailyCap": "2.000000",
                  "dailyCapResetAt": "2026-11-27T00:00:00.000Z",
                  "dailyCapReached": true,
                  "alertBudgetRemaining": "1199.940000",
                  "alertActive": true,
                  "ledgerEntryId": "led-002"
                }
                """.trimIndent()
            )
        )

        assertTrue(repository.startSession("user-br-1", sampleAlert, "BRL").isSuccess)
        server.takeRequest() // consume /sessions/start

        // First 5s -> HTTP 500 -> pending stays at 5s
        repoScope.advanceTimeBy(5_000L)
        repoScope.runCurrent()
        val failedReq = server.takeRequest()
        assertTrue(failedReq.body.readUtf8().contains(""""activeSecondsDelta":5"""))

        val afterFail = repoScope.awaitState(repository) {
            it != null && !it.timerState.isFlushing && it.timerState.lastErrorMessage != null
        }!!
        assertEquals(0, afterFail.timerState.verifiedActiveSeconds)
        assertEquals(5, afterFail.timerState.pendingActiveSeconds)
        assertNull(afterFail.timerState.stopReason)

        // Screen off for 3s -> must NOT accumulate seconds
        repository.setScreenInteractive(false)
        repoScope.advanceTimeBy(3_000L)
        repoScope.runCurrent()
        assertEquals(5, repository.activeSession.value!!.timerState.pendingActiveSeconds)

        // Screen back on for 1s -> pending becomes 6s (>= 5s flush interval) and flushes cleanly once
        repository.setScreenInteractive(true)
        repoScope.advanceTimeBy(1_000L)
        repoScope.runCurrent()
        val retryReq = server.takeRequest()
        assertTrue(retryReq.body.readUtf8().contains(""""activeSecondsDelta":6"""))

        val capped = repoScope.awaitState(repository) {
            it != null && it.timerState.isStopped && !it.timerState.isFlushing
        }!!
        assertEquals(6, capped.timerState.verifiedActiveSeconds)
        assertEquals(0, capped.timerState.pendingActiveSeconds)
        assertEquals(AttentionStopReason.DAILY_CAP_REACHED, capped.timerState.stopReason)
        assertTrue(capped.timerState.isStopped)

        // Dismiss stopped session clears StateFlow
        repository.dismissStoppedSession()
        assertNull(repository.activeSession.value)
    }

    private fun TestScope.awaitState(
        repository: AdSessionRepository,
        predicate: (ActiveAdSessionState?) -> Boolean
    ): ActiveAdSessionState? {
        val deadlineMs = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadlineMs) {
            runCurrent()
            val current = repository.activeSession.value
            if (predicate(current)) return current
            Thread.sleep(10L)
        }
        runCurrent()
        return repository.activeSession.value
    }

    @Test
    fun `marks affiliate URL invalid when backend returns unresolved click_id placeholder`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repoScope = TestScope(dispatcher)
        val api = YaytifApiFactory.createService(server.url("/").toString())
        val repository = AdSessionRepository(apiService = api, scope = repoScope)

        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {
                  "sessionId": "sess-bad-url",
                  "clickId": "clk-999",
                  "resolvedAffiliateUrl": "https://track.yaytif.com/c/ifood-br?click_id={click_id}"
                }
                """.trimIndent()
            )
        )

        val result = repository.startSession("user-br-1", sampleAlert, "BRL")
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().isAffiliateUrlValid)

        repository.endSession()
    }
}
