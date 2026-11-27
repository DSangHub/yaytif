package com.yaytif.app.data.repository

import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import com.yaytif.app.data.remote.YaytifApiService
import com.yaytif.app.data.remote.dto.SessionHeartbeatRequest
import com.yaytif.app.data.remote.dto.StartSessionRequest
import com.yaytif.app.domain.AffiliateLinkLauncher
import com.yaytif.app.domain.AffiliateUrlValidationResult
import com.yaytif.app.domain.AttentionStopReason
import com.yaytif.app.domain.AttentionTimerEngine
import com.yaytif.app.domain.AttentionTimerState
import com.yaytif.app.domain.HeartbeatFlushResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException

data class ActiveAdSessionState(
    val alert: SponsoredAlertEntity,
    val sessionId: String,
    val clickId: String,
    val resolvedAffiliateUrl: String,
    val isAffiliateUrlValid: Boolean,
    val currencyCode: String,
    val timerState: AttentionTimerState
)

/**
 * Coordinates sponsored ad sessions (`/api/v1/sessions/start`), verified attention-time
 * heartbeats (`/api/v1/sessions/{sessionId}/heartbeat`), and `{click_id}` attribution state
 * across both Compose UI and [com.yaytif.app.overlay.SponsoredOverlayService].
 */
class AdSessionRepository(
    private val apiService: YaytifApiService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val flushIntervalSeconds: Int = AttentionTimerEngine.DEFAULT_FLUSH_INTERVAL_SECONDS,
    private val tickIntervalMs: Long = AttentionTimerEngine.DEFAULT_TICK_INTERVAL_MS
) {

    private val sessionMutex = Mutex()
    private var currentEngine: AttentionTimerEngine? = null
    private var mirrorJob: Job? = null

    private val _activeSession = MutableStateFlow<ActiveAdSessionState?>(null)
    val activeSession: StateFlow<ActiveAdSessionState?> = _activeSession.asStateFlow()

    suspend fun startSession(
        userId: String,
        alert: SponsoredAlertEntity,
        currencyCode: String,
        isScreenInteractive: Boolean = true,
        isAdVisible: Boolean = true
    ): Result<ActiveAdSessionState> {
        if (userId.isBlank()) {
            return Result.failure(IllegalArgumentException("userId must not be blank"))
        }

        return sessionMutex.withLock {
            stopCurrentEngineLocked(AttentionStopReason.USER_DISMISSED)

            runCatching {
                val started = apiService.startSession(
                    StartSessionRequest(
                        userId = userId.trim(),
                        alertId = alert.id
                    )
                )

                val urlValidation = AffiliateLinkLauncher.validateAffiliateUrl(started.resolvedAffiliateUrl)
                val normalizedUrl = when (urlValidation) {
                    is AffiliateUrlValidationResult.Valid -> urlValidation.normalizedUrl
                    is AffiliateUrlValidationResult.Invalid -> started.resolvedAffiliateUrl
                }
                val isUrlValid = urlValidation is AffiliateUrlValidationResult.Valid

                val engine = AttentionTimerEngine(
                    sessionId = started.sessionId,
                    payoutPerMinute = alert.payoutPerMinuteDecimal(),
                    flushIntervalSeconds = flushIntervalSeconds,
                    initialScreenInteractive = isScreenInteractive,
                    initialAdVisible = isAdVisible,
                    onFlushHeartbeat = { sessionId, delta ->
                        submitHeartbeat(sessionId, delta)
                    }
                )

                currentEngine = engine

                val initialState = ActiveAdSessionState(
                    alert = alert,
                    sessionId = started.sessionId,
                    clickId = started.clickId,
                    resolvedAffiliateUrl = normalizedUrl,
                    isAffiliateUrlValid = isUrlValid,
                    currencyCode = currencyCode,
                    timerState = engine.state.value
                )
                _activeSession.value = initialState

                mirrorJob = scope.launch {
                    engine.state.collect { timerState ->
                        val current = _activeSession.value
                        if (current != null && current.sessionId == started.sessionId) {
                            _activeSession.value = current.copy(timerState = timerState)
                        }
                    }
                }

                engine.startTicker(scope = scope, tickIntervalMs = tickIntervalMs)
                initialState
            }
        }
    }

    fun setScreenInteractive(interactive: Boolean) {
        currentEngine?.setScreenInteractive(interactive)
    }

    fun setAdVisible(visible: Boolean) {
        currentEngine?.setAdVisible(visible)
    }

    fun setUserPaused(paused: Boolean) {
        currentEngine?.setUserPaused(paused)
    }

    suspend fun flushNow() {
        currentEngine?.flushPending(force = true)
    }

    suspend fun endSession(reason: AttentionStopReason = AttentionStopReason.USER_DISMISSED) {
        sessionMutex.withLock {
            stopCurrentEngineLocked(reason)
            _activeSession.value = null
        }
    }

    fun dismissStoppedSession() {
        val current = _activeSession.value
        if (current != null && current.timerState.isStopped) {
            mirrorJob?.cancel()
            mirrorJob = null
            currentEngine = null
            _activeSession.value = null
        }
    }

    private suspend fun stopCurrentEngineLocked(reason: AttentionStopReason) {
        val engine = currentEngine
        currentEngine = null
        if (engine != null) {
            engine.stopAndFlush(reason)
        }
        mirrorJob?.cancel()
        mirrorJob = null
    }

    private suspend fun submitHeartbeat(
        sessionId: String,
        activeSecondsDelta: Int
    ): HeartbeatFlushResult {
        val boundedDelta = activeSecondsDelta.coerceIn(
            AttentionTimerEngine.MIN_HEARTBEAT_DELTA_SECONDS,
            AttentionTimerEngine.MAX_HEARTBEAT_DELTA_SECONDS
        )
        return try {
            val response = apiService.sendSessionHeartbeat(
                sessionId = sessionId,
                request = SessionHeartbeatRequest(activeSecondsDelta = boundedDelta)
            )
            HeartbeatFlushResult.Success(
                activeSeconds = response.activeSeconds,
                creditedDelta = response.creditedDelta,
                sessionCreditedTotal = response.sessionCreditedTotal,
                walletBalance = response.walletBalance,
                dailyCapReached = response.dailyCapReached,
                alertBudgetRemaining = response.alertBudgetRemaining,
                alertActive = response.alertActive
            )
        } catch (httpEx: HttpException) {
            val code = httpEx.code()
            when {
                code == 409 || code == 429 || code >= 500 -> {
                    HeartbeatFlushResult.RetryableError("HTTP $code")
                }
                else -> {
                    HeartbeatFlushResult.TerminalStop(
                        reason = AttentionStopReason.CAMPAIGN_BUDGET_EXHAUSTED,
                        message = "HTTP $code"
                    )
                }
            }
        } catch (ioEx: IOException) {
            HeartbeatFlushResult.RetryableError(ioEx.message ?: "Network error")
        }
    }
}
