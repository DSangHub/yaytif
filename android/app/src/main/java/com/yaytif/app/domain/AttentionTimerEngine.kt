package com.yaytif.app.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode

enum class AttentionStopReason {
    DAILY_CAP_REACHED,
    CAMPAIGN_BUDGET_EXHAUSTED,
    USER_DISMISSED
}

sealed interface HeartbeatFlushResult {
    data class Success(
        val activeSeconds: Int,
        val creditedDelta: BigDecimal,
        val sessionCreditedTotal: BigDecimal,
        val walletBalance: BigDecimal,
        val dailyCapReached: Boolean,
        val alertBudgetRemaining: BigDecimal?,
        val alertActive: Boolean
    ) : HeartbeatFlushResult

    data class RetryableError(
        val message: String
    ) : HeartbeatFlushResult

    data class TerminalStop(
        val reason: AttentionStopReason,
        val message: String? = null
    ) : HeartbeatFlushResult
}

data class AttentionTimerState(
    val sessionId: String,
    val payoutPerMinute: BigDecimal,
    val isScreenInteractive: Boolean = true,
    val isAdVisible: Boolean = true,
    val isUserPaused: Boolean = false,
    val verifiedActiveSeconds: Int = 0,
    val pendingActiveSeconds: Int = 0,
    val inFlightSeconds: Int = 0,
    val confirmedEarnedAmount: BigDecimal = BigDecimal.ZERO,
    val liveEstimatedEarnedAmount: BigDecimal = BigDecimal.ZERO,
    val walletBalance: BigDecimal? = null,
    val alertBudgetRemaining: BigDecimal? = null,
    val isFlushing: Boolean = false,
    val stopReason: AttentionStopReason? = null,
    val lastErrorMessage: String? = null
) {
    val totalActiveSeconds: Int
        get() = verifiedActiveSeconds + pendingActiveSeconds + inFlightSeconds

    val isActivelyCounting: Boolean
        get() = stopReason == null && isScreenInteractive && isAdVisible && !isUserPaused

    val isStopped: Boolean
        get() = stopReason != null
}

/**
 * Pure, unit-testable state machine for verified attention-time tracking.
 *
 * Guarantees:
 * - Only counts seconds when the screen is interactive, the ad is visible, and not paused/stopped.
 * - Emits bounded heartbeat deltas in `MIN_HEARTBEAT_DELTA_SECONDS..MAX_HEARTBEAT_DELTA_SECONDS`.
 * - Retries failed flushes by returning in-flight seconds to the pending queue without double-counting.
 * - Performs all monetary calculations strictly with [BigDecimal] (ROUND_DOWN to 6 decimals).
 * - Stops immediately when daily cap or campaign budget is reached.
 */
class AttentionTimerEngine(
    val sessionId: String,
    val payoutPerMinute: BigDecimal,
    private val flushIntervalSeconds: Int = DEFAULT_FLUSH_INTERVAL_SECONDS,
    private val initialScreenInteractive: Boolean = true,
    private val initialAdVisible: Boolean = true,
    private val onFlushHeartbeat: suspend (sessionId: String, activeSecondsDelta: Int) -> HeartbeatFlushResult
) {

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(payoutPerMinute.compareTo(BigDecimal.ZERO) >= 0) { "payoutPerMinute must be non-negative" }
        require(flushIntervalSeconds in MIN_HEARTBEAT_DELTA_SECONDS..MAX_HEARTBEAT_DELTA_SECONDS) {
            "flushIntervalSeconds must be in $MIN_HEARTBEAT_DELTA_SECONDS..$MAX_HEARTBEAT_DELTA_SECONDS"
        }
    }

    private val flushMutex = Mutex()
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow(
        AttentionTimerState(
            sessionId = sessionId,
            payoutPerMinute = payoutPerMinute,
            isScreenInteractive = initialScreenInteractive,
            isAdVisible = initialAdVisible
        )
    )
    val state: StateFlow<AttentionTimerState> = _state.asStateFlow()

    fun startTicker(
        scope: CoroutineScope,
        tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS
    ) {
        require(tickIntervalMs > 0L) { "tickIntervalMs must be strictly positive" }
        if (tickerJob?.isActive == true || _state.value.isStopped) {
            return
        }
        tickerJob = scope.launch {
            while (isActive && !_state.value.isStopped) {
                delay(tickIntervalMs)
                onTick(seconds = 1)
            }
        }
    }

    fun setScreenInteractive(interactive: Boolean) {
        _state.update { current ->
            if (current.isScreenInteractive == interactive) current
            else current.copy(isScreenInteractive = interactive)
        }
    }

    fun setAdVisible(visible: Boolean) {
        _state.update { current ->
            if (current.isAdVisible == visible) current
            else current.copy(isAdVisible = visible)
        }
    }

    fun setUserPaused(paused: Boolean) {
        _state.update { current ->
            if (current.isUserPaused == paused) current
            else current.copy(isUserPaused = paused)
        }
    }

    /**
     * Advances the timer by [seconds] if and only if engagement is actively verified.
     * Automatically flushes bounded 1..60s deltas once [flushIntervalSeconds] is reached.
     */
    suspend fun onTick(seconds: Int = 1) {
        if (seconds <= 0) return

        var shouldFlush = false
        _state.update { current ->
            if (!current.isActivelyCounting) {
                current
            } else {
                val updatedPending = current.pendingActiveSeconds + seconds
                shouldFlush = updatedPending >= flushIntervalSeconds && !current.isFlushing
                current.copy(
                    pendingActiveSeconds = updatedPending,
                    liveEstimatedEarnedAmount = computeLiveEstimate(
                        confirmedEarned = current.confirmedEarnedAmount,
                        payoutPerMinute = current.payoutPerMinute,
                        unconfirmedSeconds = updatedPending + current.inFlightSeconds
                    )
                )
            }
        }

        if (shouldFlush) {
            flushPending(force = false)
        }
    }

    /**
     * Flushes pending verified seconds in bounded chunks of 1..60 seconds.
     * Serialized via [flushMutex] so concurrent callers can never double-send the same seconds.
     */
    suspend fun flushPending(force: Boolean = true) {
        flushMutex.withLock {
            while (true) {
                val snapshot = _state.value
                if (snapshot.isStopped) {
                    return
                }
                if (snapshot.pendingActiveSeconds < MIN_HEARTBEAT_DELTA_SECONDS) {
                    return
                }
                if (!force && snapshot.pendingActiveSeconds < flushIntervalSeconds) {
                    return
                }

                val delta = snapshot.pendingActiveSeconds.coerceIn(
                    MIN_HEARTBEAT_DELTA_SECONDS,
                    MAX_HEARTBEAT_DELTA_SECONDS
                )

                _state.update { current ->
                    val nextPending = (current.pendingActiveSeconds - delta).coerceAtLeast(0)
                    val nextInFlight = current.inFlightSeconds + delta
                    current.copy(
                        pendingActiveSeconds = nextPending,
                        inFlightSeconds = nextInFlight,
                        isFlushing = true
                    )
                }

                val result = onFlushHeartbeat(sessionId, delta)
                when (result) {
                    is HeartbeatFlushResult.Success -> {
                        val reachedBudgetExhaustion = !result.alertActive ||
                            (result.alertBudgetRemaining != null &&
                                result.alertBudgetRemaining.compareTo(BigDecimal.ZERO) <= 0)
                        val stopReason = when {
                            result.dailyCapReached -> AttentionStopReason.DAILY_CAP_REACHED
                            reachedBudgetExhaustion -> AttentionStopReason.CAMPAIGN_BUDGET_EXHAUSTED
                            else -> null
                        }

                        _state.update { current ->
                            val remainingInFlight = (current.inFlightSeconds - delta).coerceAtLeast(0)
                            val updatedVerified = maxOf(
                                current.verifiedActiveSeconds + delta,
                                result.activeSeconds
                            )
                            val remainingPending = if (stopReason != null) 0 else current.pendingActiveSeconds
                            val confirmedTotal = result.sessionCreditedTotal
                            current.copy(
                                verifiedActiveSeconds = updatedVerified,
                                pendingActiveSeconds = remainingPending,
                                inFlightSeconds = remainingInFlight,
                                confirmedEarnedAmount = confirmedTotal,
                                liveEstimatedEarnedAmount = if (stopReason != null) {
                                    confirmedTotal
                                } else {
                                    computeLiveEstimate(
                                        confirmedEarned = confirmedTotal,
                                        payoutPerMinute = current.payoutPerMinute,
                                        unconfirmedSeconds = remainingPending + remainingInFlight
                                    )
                                },
                                walletBalance = result.walletBalance,
                                alertBudgetRemaining = result.alertBudgetRemaining,
                                isFlushing = false,
                                stopReason = stopReason,
                                lastErrorMessage = null
                            )
                        }

                        if (stopReason != null) {
                            tickerJob?.cancel()
                            return
                        }
                    }

                    is HeartbeatFlushResult.RetryableError -> {
                        _state.update { current ->
                            val remainingInFlight = (current.inFlightSeconds - delta).coerceAtLeast(0)
                            val restoredPending = current.pendingActiveSeconds + delta
                            current.copy(
                                pendingActiveSeconds = restoredPending,
                                inFlightSeconds = remainingInFlight,
                                liveEstimatedEarnedAmount = computeLiveEstimate(
                                    confirmedEarned = current.confirmedEarnedAmount,
                                    payoutPerMinute = current.payoutPerMinute,
                                    unconfirmedSeconds = restoredPending + remainingInFlight
                                ),
                                isFlushing = false,
                                lastErrorMessage = result.message
                            )
                        }
                        return
                    }

                    is HeartbeatFlushResult.TerminalStop -> {
                        _state.update { current ->
                            val remainingInFlight = (current.inFlightSeconds - delta).coerceAtLeast(0)
                            current.copy(
                                pendingActiveSeconds = 0,
                                inFlightSeconds = remainingInFlight,
                                liveEstimatedEarnedAmount = current.confirmedEarnedAmount,
                                isFlushing = false,
                                stopReason = result.reason,
                                lastErrorMessage = result.message
                            )
                        }
                        tickerJob?.cancel()
                        return
                    }
                }
            }
        }
    }

    /**
     * Stops the ticker, flushes any remaining verified seconds, and marks the session stopped.
     */
    suspend fun stopAndFlush(reason: AttentionStopReason = AttentionStopReason.USER_DISMISSED) {
        tickerJob?.cancel()
        tickerJob = null

        if (!_state.value.isStopped && _state.value.pendingActiveSeconds > 0) {
            flushPending(force = true)
        }

        _state.update { current ->
            val finalReason = current.stopReason ?: reason
            current.copy(
                stopReason = finalReason,
                isFlushing = false
            )
        }
    }

    companion object {
        const val MIN_HEARTBEAT_DELTA_SECONDS = 1
        const val MAX_HEARTBEAT_DELTA_SECONDS = 60
        const val DEFAULT_FLUSH_INTERVAL_SECONDS = 10
        const val DEFAULT_TICK_INTERVAL_MS = 1_000L

        private val SECONDS_PER_MINUTE = BigDecimal("60")
        private const val MONETARY_SCALE = 6

        /**
         * Calculates exact attention payout using [BigDecimal] matching backend rounding:
         * `(payoutPerMinute * activeSeconds) / 60`, rounded DOWN to 6 decimal places.
         */
        fun calculateAccruedPayout(
            payoutPerMinute: BigDecimal,
            activeSeconds: Int
        ): BigDecimal {
            if (activeSeconds <= 0 || payoutPerMinute.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO.setScale(MONETARY_SCALE, RoundingMode.DOWN)
            }
            return payoutPerMinute
                .multiply(BigDecimal.valueOf(activeSeconds.toLong()))
                .divide(SECONDS_PER_MINUTE, MONETARY_SCALE, RoundingMode.DOWN)
        }

        private fun computeLiveEstimate(
            confirmedEarned: BigDecimal,
            payoutPerMinute: BigDecimal,
            unconfirmedSeconds: Int
        ): BigDecimal {
            val unconfirmedPayout = calculateAccruedPayout(payoutPerMinute, unconfirmedSeconds)
            return confirmedEarned
                .add(unconfirmedPayout)
                .setScale(MONETARY_SCALE, RoundingMode.DOWN)
        }

        fun formatElapsedSeconds(totalSeconds: Int): String {
            val clamped = totalSeconds.coerceAtLeast(0)
            val minutes = clamped / 60
            val seconds = clamped % 60
            return "%02d:%02d".format(minutes, seconds)
        }
    }
}
