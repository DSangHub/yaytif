package com.yaytif.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class AttentionTimerEngineTest {

    @Test
    fun `counts seconds only while screen is interactive, ad is visible, and not paused`() = runTest {
        val deltas = mutableListOf<Int>()
        val engine = AttentionTimerEngine(
            sessionId = "sess-1",
            payoutPerMinute = BigDecimal("0.600000"),
            flushIntervalSeconds = 10,
            onFlushHeartbeat = { _, delta ->
                deltas += delta
                HeartbeatFlushResult.Success(
                    activeSeconds = deltas.sum(),
                    creditedDelta = AttentionTimerEngine.calculateAccruedPayout(BigDecimal("0.600000"), delta),
                    sessionCreditedTotal = AttentionTimerEngine.calculateAccruedPayout(
                        BigDecimal("0.600000"),
                        deltas.sum()
                    ),
                    walletBalance = BigDecimal("10.000000"),
                    dailyCapReached = false,
                    alertBudgetRemaining = BigDecimal("100.000000"),
                    alertActive = true
                )
            }
        )

        engine.onTick(3)
        assertEquals(3, engine.state.value.totalActiveSeconds)
        assertEquals(3, engine.state.value.pendingActiveSeconds)
        assertEquals(BigDecimal("0.030000"), engine.state.value.liveEstimatedEarnedAmount)

        // Screen off -> ticks are ignored
        engine.setScreenInteractive(false)
        assertFalse(engine.state.value.isActivelyCounting)
        engine.onTick(5)
        assertEquals(3, engine.state.value.totalActiveSeconds)

        // Screen on, ad hidden -> ticks are ignored
        engine.setScreenInteractive(true)
        engine.setAdVisible(false)
        assertFalse(engine.state.value.isActivelyCounting)
        engine.onTick(4)
        assertEquals(3, engine.state.value.totalActiveSeconds)

        // Ad visible, user paused -> ticks are ignored
        engine.setAdVisible(true)
        engine.setUserPaused(true)
        assertFalse(engine.state.value.isActivelyCounting)
        engine.onTick(4)
        assertEquals(3, engine.state.value.totalActiveSeconds)

        // Resumed -> ticks resume counting
        engine.setUserPaused(false)
        assertTrue(engine.state.value.isActivelyCounting)
        engine.onTick(2)
        assertEquals(5, engine.state.value.totalActiveSeconds)
        assertEquals(BigDecimal("0.050000"), engine.state.value.liveEstimatedEarnedAmount)
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `emits bounded 1 to 60 second heartbeat chunks when pending exceeds 60 seconds`() = runTest {
        val deltas = mutableListOf<Int>()
        var cumulativeSeconds = 0
        var cumulativeCredited = BigDecimal.ZERO
        val rate = BigDecimal("0.300000")

        val engine = AttentionTimerEngine(
            sessionId = "sess-chunk",
            payoutPerMinute = rate,
            flushIntervalSeconds = 60,
            onFlushHeartbeat = { _, delta ->
                assertTrue("Delta must be bounded 1..60, got $delta", delta in 1..60)
                deltas += delta
                cumulativeSeconds += delta
                val deltaEarned = AttentionTimerEngine.calculateAccruedPayout(rate, delta)
                cumulativeCredited = cumulativeCredited.add(deltaEarned)
                HeartbeatFlushResult.Success(
                    activeSeconds = cumulativeSeconds,
                    creditedDelta = deltaEarned,
                    sessionCreditedTotal = cumulativeCredited,
                    walletBalance = cumulativeCredited,
                    dailyCapReached = false,
                    alertBudgetRemaining = BigDecimal("500.000000"),
                    alertActive = true
                )
            }
        )

        // Simulate 135 active seconds accrued before flush finishes
        engine.onTick(135)
        // onTick with flushInterval=60 triggers non-forced flush: drains 60, 60 (leaves 15 pending)
        assertEquals(listOf(60, 60), deltas)
        assertEquals(120, engine.state.value.verifiedActiveSeconds)
        assertEquals(15, engine.state.value.pendingActiveSeconds)

        // Forced flush drains remaining 15 seconds
        engine.flushPending(force = true)
        assertEquals(listOf(60, 60, 15), deltas)
        assertEquals(135, engine.state.value.verifiedActiveSeconds)
        assertEquals(0, engine.state.value.pendingActiveSeconds)
        assertEquals(BigDecimal("0.675000"), engine.state.value.confirmedEarnedAmount)
    }

    @Test
    fun `retries failed heartbeat without double-counting or losing seconds`() = runTest {
        var attempt = 0
        val receivedDeltas = mutableListOf<Int>()
        val rate = BigDecimal("0.600000")

        val engine = AttentionTimerEngine(
            sessionId = "sess-retry",
            payoutPerMinute = rate,
            flushIntervalSeconds = 10,
            onFlushHeartbeat = { _, delta ->
                attempt += 1
                receivedDeltas += delta
                if (attempt == 1) {
                    HeartbeatFlushResult.RetryableError("HTTP 503")
                } else {
                    val credited = AttentionTimerEngine.calculateAccruedPayout(rate, delta)
                    HeartbeatFlushResult.Success(
                        activeSeconds = delta,
                        creditedDelta = credited,
                        sessionCreditedTotal = credited,
                        walletBalance = BigDecimal("5.000000").add(credited),
                        dailyCapReached = false,
                        alertBudgetRemaining = BigDecimal("100.000000"),
                        alertActive = true
                    )
                }
            }
        )

        // First 10 seconds trigger flush -> fails with RetryableError
        engine.onTick(10)
        assertEquals(listOf(10), receivedDeltas)
        assertEquals(0, engine.state.value.verifiedActiveSeconds)
        assertEquals(10, engine.state.value.pendingActiveSeconds)
        assertEquals(0, engine.state.value.inFlightSeconds)
        assertEquals(10, engine.state.value.totalActiveSeconds)
        assertEquals("HTTP 503", engine.state.value.lastErrorMessage)
        assertNull(engine.state.value.stopReason)

        // 5 more seconds accrue -> next flush sends combined 15s cleanly once
        engine.onTick(5)
        assertEquals(listOf(10, 15), receivedDeltas)
        assertEquals(15, engine.state.value.verifiedActiveSeconds)
        assertEquals(0, engine.state.value.pendingActiveSeconds)
        assertEquals(0, engine.state.value.inFlightSeconds)
        assertEquals(15, engine.state.value.totalActiveSeconds)
        assertEquals(BigDecimal("0.150000"), engine.state.value.confirmedEarnedAmount)
        assertNull(engine.state.value.lastErrorMessage)
    }

    @Test
    fun `stops immediately when daily cap is reached and ignores subsequent ticks`() = runTest {
        var flushCount = 0
        val engine = AttentionTimerEngine(
            sessionId = "sess-cap",
            payoutPerMinute = BigDecimal("0.180000"),
            flushIntervalSeconds = 10,
            onFlushHeartbeat = { _, delta ->
                flushCount += 1
                HeartbeatFlushResult.Success(
                    activeSeconds = delta,
                    creditedDelta = BigDecimal("0.020000"),
                    sessionCreditedTotal = BigDecimal("0.020000"),
                    walletBalance = BigDecimal("2.000000"),
                    dailyCapReached = true,
                    alertBudgetRemaining = BigDecimal("500.000000"),
                    alertActive = true
                )
            }
        )

        engine.onTick(10)
        assertEquals(AttentionStopReason.DAILY_CAP_REACHED, engine.state.value.stopReason)
        assertTrue(engine.state.value.isStopped)
        assertFalse(engine.state.value.isActivelyCounting)
        assertEquals(BigDecimal("0.020000"), engine.state.value.confirmedEarnedAmount)
        assertEquals(BigDecimal("0.020000"), engine.state.value.liveEstimatedEarnedAmount)

        // Further ticks do nothing and do not trigger more flushes
        engine.onTick(30)
        assertEquals(1, flushCount)
        assertEquals(10, engine.state.value.totalActiveSeconds)
    }

    @Test
    fun `stops immediately when campaign budget is exhausted or alert becomes inactive`() = runTest {
        val engine = AttentionTimerEngine(
            sessionId = "sess-budget",
            payoutPerMinute = BigDecimal("0.180000"),
            flushIntervalSeconds = 10,
            onFlushHeartbeat = { _, delta ->
                HeartbeatFlushResult.Success(
                    activeSeconds = delta,
                    creditedDelta = BigDecimal("0.030000"),
                    sessionCreditedTotal = BigDecimal("0.030000"),
                    walletBalance = BigDecimal("1.030000"),
                    dailyCapReached = false,
                    alertBudgetRemaining = BigDecimal("0.000000"),
                    alertActive = false
                )
            }
        )

        engine.onTick(10)
        assertEquals(AttentionStopReason.CAMPAIGN_BUDGET_EXHAUSTED, engine.state.value.stopReason)
        assertTrue(engine.state.value.isStopped)
        assertEquals(0, engine.state.value.pendingActiveSeconds)
    }

    @Test
    fun `stopAndFlush drains remaining verified seconds before marking user dismissed`() = runTest {
        val flushed = mutableListOf<Int>()
        val engine = AttentionTimerEngine(
            sessionId = "sess-stop",
            payoutPerMinute = BigDecimal("0.600000"),
            flushIntervalSeconds = 10,
            onFlushHeartbeat = { _, delta ->
                flushed += delta
                val credited = AttentionTimerEngine.calculateAccruedPayout(BigDecimal("0.600000"), delta)
                HeartbeatFlushResult.Success(
                    activeSeconds = delta,
                    creditedDelta = credited,
                    sessionCreditedTotal = credited,
                    walletBalance = credited,
                    dailyCapReached = false,
                    alertBudgetRemaining = BigDecimal("100.000000"),
                    alertActive = true
                )
            }
        )

        engine.startTicker(this, tickIntervalMs = 1_000L)
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(4, engine.state.value.pendingActiveSeconds)
        assertTrue(flushed.isEmpty())

        engine.stopAndFlush()
        assertEquals(listOf(4), flushed)
        assertEquals(4, engine.state.value.verifiedActiveSeconds)
        assertEquals(0, engine.state.value.pendingActiveSeconds)
        assertEquals(AttentionStopReason.USER_DISMISSED, engine.state.value.stopReason)
    }

    @Test
    fun `calculateAccruedPayout uses exact BigDecimal ROUND_DOWN to 6 decimal places`() {
        // 0.10 / 60 * 1 = 0.0016666... -> ROUND_DOWN -> 0.001666
        assertEquals(
            BigDecimal("0.001666"),
            AttentionTimerEngine.calculateAccruedPayout(BigDecimal("0.10"), 1)
        )
        // 0.18 / 60 * 10 = 0.030000
        assertEquals(
            BigDecimal("0.030000"),
            AttentionTimerEngine.calculateAccruedPayout(BigDecimal("0.180000"), 10)
        )
        // High-value African currency rate: 25.50 NGN/min * 7s / 60 = 2.975000
        assertEquals(
            BigDecimal("2.975000"),
            AttentionTimerEngine.calculateAccruedPayout(BigDecimal("25.500000"), 7)
        )
        // Zero or negative seconds returns 0.000000
        assertEquals(
            BigDecimal("0.000000"),
            AttentionTimerEngine.calculateAccruedPayout(BigDecimal("0.18"), 0)
        )
        assertEquals(
            BigDecimal("0.000000"),
            AttentionTimerEngine.calculateAccruedPayout(BigDecimal("0.18"), -5)
        )
    }

    @Test
    fun `formatElapsedSeconds formats MM colon SS cleanly`() {
        assertEquals("00:00", AttentionTimerEngine.formatElapsedSeconds(0))
        assertEquals("00:09", AttentionTimerEngine.formatElapsedSeconds(9))
        assertEquals("01:05", AttentionTimerEngine.formatElapsedSeconds(65))
        assertEquals("10:00", AttentionTimerEngine.formatElapsedSeconds(600))
        assertEquals("00:00", AttentionTimerEngine.formatElapsedSeconds(-12))
    }
}
