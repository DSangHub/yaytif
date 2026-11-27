package com.yaytif.app.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.dto.LedgerEntryDto
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.data.remote.dto.PayoutRequestDto
import com.yaytif.app.data.repository.PayoutSubmissionResult
import com.yaytif.app.data.repository.WalletRepository
import com.yaytif.app.domain.DestinationErrorReason
import com.yaytif.app.domain.DestinationValidationResult
import com.yaytif.app.domain.MarketConfig
import com.yaytif.app.domain.MoneyFormatter
import com.yaytif.app.domain.PayoutRailValidator
import com.yaytif.app.domain.PayoutValidationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

enum class WalletTab {
    WITHDRAW,
    LEDGER,
    PAYOUTS
}

data class DailyCapProgress(
    val earned: BigDecimal = BigDecimal.ZERO,
    val capLimit: BigDecimal = BigDecimal("10.00"),
    val fraction: BigDecimal = BigDecimal.ZERO,
    val percent: Int = 0,
    val isCapReached: Boolean = false
)

data class ResetCountdown(
    val hours: Long,
    val minutes: Long,
    val isResettingSoon: Boolean
)

data class PendingPayoutConfirmation(
    val rail: PayoutRailDto,
    val normalizedDestination: String,
    val maskedDestination: String,
    val amount: BigDecimal,
    val currency: String
)

sealed interface WalletAmountError {
    data object InvalidFormat : WalletAmountError
    data class BelowMinimum(val formattedMinimum: String) : WalletAmountError
    data object InsufficientBalance : WalletAmountError
}

sealed interface WalletFeedback {
    data object PayoutSubmitted : WalletFeedback
    data object InsufficientBalanceConflict : WalletFeedback
    data object ValidationRejected : WalletFeedback
    data object AccountRequired : WalletFeedback
    data object NetworkError : WalletFeedback
    data object LoadError : WalletFeedback
}

data class WalletUiState(
    val userId: String? = null,
    val countryCode: String = MarketConfig.DEFAULT_COUNTRY_CODE,
    val currency: String = MarketConfig.DEFAULT_CURRENCY,
    val walletBalance: BigDecimal = BigDecimal.ZERO,
    val dailyTimeEarned: BigDecimal = BigDecimal.ZERO,
    val dailyCapProgress: DailyCapProgress = DailyCapProgress(),
    val dailyCapResetAtIso: String? = null,
    val resetCountdown: ResetCountdown? = null,
    val minimumPayoutAmount: BigDecimal = PayoutRailValidator.minimumWithdrawalAmount(MarketConfig.DEFAULT_CURRENCY),
    val availableRails: List<PayoutRailDto> = PayoutRailValidator.orderedRailsForCountry(MarketConfig.DEFAULT_COUNTRY_CODE),
    val selectedRail: PayoutRailDto = PayoutRailValidator.orderedRailsForCountry(MarketConfig.DEFAULT_COUNTRY_CODE).first(),
    val selectedTab: WalletTab = WalletTab.WITHDRAW,
    val destinationInput: String = "",
    val amountInput: String = "",
    val destinationError: DestinationErrorReason? = null,
    val amountError: WalletAmountError? = null,
    val pendingConfirmation: PendingPayoutConfirmation? = null,
    val ledgerEntries: List<LedgerEntryDto> = emptyList(),
    val hasMoreLedger: Boolean = false,
    val nextLedgerCursor: String? = null,
    val isLoadingMoreLedger: Boolean = false,
    val payouts: List<PayoutRequestDto> = emptyList(),
    val isRefreshing: Boolean = false,
    val isSubmittingPayout: Boolean = false,
    val feedback: WalletFeedback? = null
)

class WalletViewModel(
    private val walletRepository: WalletRepository,
    private val preferencesStore: UserPreferencesStore,
    private val clock: () -> Instant = { Instant.now() },
    private val ledgerPageSize: Int = WalletRepository.DEFAULT_LEDGER_PAGE_SIZE
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    private val submitInFlight = AtomicBoolean(false)
    private val loadMoreInFlight = AtomicBoolean(false)
    private var lastLoadedUserId: String? = null

    init {
        viewModelScope.launch {
            preferencesStore.preferencesFlow.collect { prefs ->
                val rails = PayoutRailValidator.orderedRailsForCountry(prefs.countryCode)
                val minAmount = PayoutRailValidator.minimumWithdrawalAmount(prefs.currency)
                val capLimit = PayoutRailValidator.dailyAttentionCap(prefs.currency)

                _uiState.update { current ->
                    val keptRail = if (current.selectedRail in rails) current.selectedRail else rails.first()
                    val updatedCap = computeDailyCapProgress(current.dailyTimeEarned, capLimit)
                    current.copy(
                        userId = prefs.userId,
                        countryCode = prefs.countryCode,
                        currency = prefs.currency,
                        availableRails = rails,
                        selectedRail = keptRail,
                        minimumPayoutAmount = minAmount,
                        dailyCapProgress = updatedCap
                    )
                }

                val uid = prefs.userId
                if (!uid.isNullOrBlank() && uid != lastLoadedUserId) {
                    lastLoadedUserId = uid
                    refresh()
                }
            }
        }
    }

    fun selectTab(tab: WalletTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun selectRail(rail: PayoutRailDto) {
        _uiState.update {
            it.copy(
                selectedRail = rail,
                destinationError = null,
                feedback = null
            )
        }
    }

    fun onDestinationChange(value: String) {
        _uiState.update {
            it.copy(
                destinationInput = value,
                destinationError = null,
                feedback = null
            )
        }
    }

    fun onAmountChange(value: String) {
        _uiState.update {
            it.copy(
                amountInput = value,
                amountError = null,
                feedback = null
            )
        }
    }

    fun applyQuickAmount(percentage: Int) {
        val current = _uiState.value
        val computed = PayoutRailValidator.calculateQuickAmount(current.walletBalance, percentage)
        _uiState.update {
            it.copy(
                amountInput = computed.toPlainString(),
                amountError = null,
                feedback = null
            )
        }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    fun requestConfirmation() {
        val current = _uiState.value
        if (current.isSubmittingPayout) return

        val destinationResult = PayoutRailValidator.validateDestination(
            rail = current.selectedRail,
            rawDestination = current.destinationInput,
            countryCode = current.countryCode
        )
        val amountResult = PayoutRailValidator.validateAmount(
            rawAmount = current.amountInput,
            availableBalance = current.walletBalance,
            currencyCode = current.currency
        )

        val destError = (destinationResult as? DestinationValidationResult.Invalid)?.reason
        val amtError = when (amountResult) {
            is PayoutValidationResult.Valid -> null
            PayoutValidationResult.InvalidFormat,
            PayoutValidationResult.TooManyDecimals,
            PayoutValidationResult.MustBePositive -> WalletAmountError.InvalidFormat
            is PayoutValidationResult.BelowMinimum -> WalletAmountError.BelowMinimum(
                MoneyFormatter.formatAmount(amountResult.minimum, current.currency)
            )
            is PayoutValidationResult.InsufficientBalance -> WalletAmountError.InsufficientBalance
        }

        if (destError != null || amtError != null) {
            _uiState.update {
                it.copy(
                    destinationError = destError,
                    amountError = amtError,
                    pendingConfirmation = null,
                    feedback = null
                )
            }
            return
        }

        if (current.userId.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    destinationError = null,
                    amountError = null,
                    pendingConfirmation = null,
                    feedback = WalletFeedback.AccountRequired
                )
            }
            return
        }

        val normalizedDest = (destinationResult as DestinationValidationResult.Valid).normalizedDestination
        val validatedAmount = (amountResult as PayoutValidationResult.Valid).amount

        _uiState.update {
            it.copy(
                destinationError = null,
                amountError = null,
                feedback = null,
                pendingConfirmation = PendingPayoutConfirmation(
                    rail = current.selectedRail,
                    normalizedDestination = normalizedDest,
                    maskedDestination = PayoutRailValidator.maskDestination(current.selectedRail, normalizedDest),
                    amount = validatedAmount,
                    currency = current.currency
                )
            )
        }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(pendingConfirmation = null) }
    }

    fun confirmAndSubmitPayout() {
        if (!submitInFlight.compareAndSet(false, true)) {
            return
        }

        val current = _uiState.value
        val confirmation = current.pendingConfirmation
        val userId = current.userId

        if (confirmation == null) {
            submitInFlight.set(false)
            return
        }

        if (userId.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    feedback = WalletFeedback.AccountRequired
                )
            }
            submitInFlight.set(false)
            return
        }

        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                isSubmittingPayout = true,
                feedback = null
            )
        }

        viewModelScope.launch {
            try {
                when (
                    val result = walletRepository.submitPayout(
                        userId = userId,
                        rail = confirmation.rail,
                        destination = confirmation.normalizedDestination,
                        amount = confirmation.amount
                    )
                ) {
                    is PayoutSubmissionResult.Success -> {
                        _uiState.update { state ->
                            val updatedPayouts = listOf(result.response.payout) +
                                state.payouts.filterNot { it.id == result.response.payout.id }
                            state.copy(
                                walletBalance = result.response.walletBalance,
                                payouts = updatedPayouts,
                                destinationInput = "",
                                amountInput = "",
                                destinationError = null,
                                amountError = null,
                                isSubmittingPayout = false,
                                feedback = WalletFeedback.PayoutSubmitted
                            )
                        }
                        refreshInternal(userId = userId, preserveFeedback = true)
                    }

                    PayoutSubmissionResult.InsufficientFunds -> {
                        _uiState.update {
                            it.copy(
                                isSubmittingPayout = false,
                                feedback = WalletFeedback.InsufficientBalanceConflict
                            )
                        }
                    }

                    PayoutSubmissionResult.ValidationError -> {
                        _uiState.update {
                            it.copy(
                                isSubmittingPayout = false,
                                feedback = WalletFeedback.ValidationRejected
                            )
                        }
                    }

                    PayoutSubmissionResult.UserNotFound -> {
                        _uiState.update {
                            it.copy(
                                isSubmittingPayout = false,
                                feedback = WalletFeedback.AccountRequired
                            )
                        }
                    }

                    is PayoutSubmissionResult.NetworkError -> {
                        _uiState.update {
                            it.copy(
                                isSubmittingPayout = false,
                                feedback = WalletFeedback.NetworkError
                            )
                        }
                    }
                }
            } finally {
                submitInFlight.set(false)
            }
        }
    }

    fun refresh() {
        val userId = _uiState.value.userId
        if (userId.isNullOrBlank()) {
            _uiState.update { it.copy(feedback = WalletFeedback.AccountRequired) }
            return
        }
        viewModelScope.launch {
            refreshInternal(userId = userId, preserveFeedback = false)
        }
    }

    private suspend fun refreshInternal(userId: String, preserveFeedback: Boolean) {
        _uiState.update {
            it.copy(
                isRefreshing = true,
                feedback = if (preserveFeedback) it.feedback else null
            )
        }

        runCatching {
            coroutineScope {
                val ledgerDeferred = async {
                    walletRepository.fetchWalletLedger(
                        userId = userId,
                        cursor = null,
                        limit = ledgerPageSize
                    )
                }
                val payoutsDeferred = async {
                    walletRepository.fetchPayouts(userId = userId)
                }
                ledgerDeferred.await() to payoutsDeferred.await()
            }
        }.onSuccess { (ledgerResponse, payouts) ->
            val wallet = ledgerResponse.wallet
            val capLimit = PayoutRailValidator.dailyAttentionCap(wallet.currency)
            val minWithdrawal = PayoutRailValidator.minimumWithdrawalAmount(wallet.currency)
            val capProgress = computeDailyCapProgress(wallet.dailyTimeEarned, capLimit)
            val countdown = computeResetCountdown(wallet.dailyCapResetAt, clock())

            _uiState.update { state ->
                state.copy(
                    walletBalance = wallet.walletBalance,
                    dailyTimeEarned = wallet.dailyTimeEarned,
                    dailyCapProgress = capProgress,
                    dailyCapResetAtIso = wallet.dailyCapResetAt,
                    resetCountdown = countdown,
                    currency = wallet.currency,
                    minimumPayoutAmount = minWithdrawal,
                    ledgerEntries = ledgerResponse.entries,
                    hasMoreLedger = ledgerResponse.pagination.hasMore,
                    nextLedgerCursor = ledgerResponse.pagination.nextCursor,
                    payouts = payouts,
                    isRefreshing = false
                )
            }
        }.onFailure {
            _uiState.update { state ->
                state.copy(
                    isRefreshing = false,
                    feedback = if (preserveFeedback) state.feedback else WalletFeedback.LoadError
                )
            }
        }
    }

    fun loadMoreLedger() {
        val current = _uiState.value
        val userId = current.userId
        val cursor = current.nextLedgerCursor
        if (userId.isNullOrBlank() || !current.hasMoreLedger || cursor.isNullOrBlank()) {
            return
        }
        if (!loadMoreInFlight.compareAndSet(false, true)) {
            return
        }

        _uiState.update { it.copy(isLoadingMoreLedger = true) }

        viewModelScope.launch {
            try {
                val page = walletRepository.fetchWalletLedger(
                    userId = userId,
                    cursor = cursor,
                    limit = ledgerPageSize
                )
                val wallet = page.wallet
                val capLimit = PayoutRailValidator.dailyAttentionCap(wallet.currency)
                val capProgress = computeDailyCapProgress(wallet.dailyTimeEarned, capLimit)
                val countdown = computeResetCountdown(wallet.dailyCapResetAt, clock())

                _uiState.update { state ->
                    val existingIds = state.ledgerEntries.mapTo(HashSet()) { it.id }
                    val uniqueNew = page.entries.filter { existingIds.add(it.id) }
                    state.copy(
                        walletBalance = wallet.walletBalance,
                        dailyTimeEarned = wallet.dailyTimeEarned,
                        dailyCapProgress = capProgress,
                        dailyCapResetAtIso = wallet.dailyCapResetAt,
                        resetCountdown = countdown,
                        ledgerEntries = state.ledgerEntries + uniqueNew,
                        hasMoreLedger = page.pagination.hasMore,
                        nextLedgerCursor = page.pagination.nextCursor,
                        isLoadingMoreLedger = false
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingMoreLedger = false,
                        feedback = WalletFeedback.LoadError
                    )
                }
            } finally {
                loadMoreInFlight.set(false)
            }
        }
    }

    companion object {
        private val HUNDRED = BigDecimal("100")

        fun computeDailyCapProgress(
            earned: BigDecimal,
            capLimit: BigDecimal
        ): DailyCapProgress {
            val safeEarned = earned.max(BigDecimal.ZERO)
            if (capLimit.compareTo(BigDecimal.ZERO) <= 0) {
                return DailyCapProgress(
                    earned = safeEarned,
                    capLimit = BigDecimal.ZERO,
                    fraction = BigDecimal.ZERO,
                    percent = 0,
                    isCapReached = false
                )
            }
            val rawRatio = safeEarned.divide(capLimit, 4, RoundingMode.DOWN)
            val clampedFraction = rawRatio.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
            val percent = clampedFraction
                .multiply(HUNDRED)
                .setScale(0, RoundingMode.HALF_UP)
                .toInt()
                .coerceIn(0, 100)
            return DailyCapProgress(
                earned = safeEarned,
                capLimit = capLimit,
                fraction = clampedFraction,
                percent = percent,
                isCapReached = safeEarned.compareTo(capLimit) >= 0
            )
        }

        fun computeResetCountdown(
            resetAtIso: String?,
            now: Instant
        ): ResetCountdown? {
            if (resetAtIso.isNullOrBlank()) return null
            val target = runCatching { Instant.parse(resetAtIso) }.getOrNull() ?: return null
            val remaining = Duration.between(now, target)
            if (remaining.isZero || remaining.isNegative) {
                return ResetCountdown(hours = 0L, minutes = 0L, isResettingSoon = true)
            }
            val totalMinutes = remaining.toMinutes()
            return ResetCountdown(
                hours = totalMinutes / 60L,
                minutes = totalMinutes % 60L,
                isResettingSoon = totalMinutes < 1L
            )
        }
    }

    class Factory(
        private val walletRepository: WalletRepository,
        private val preferencesStore: UserPreferencesStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WalletViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return WalletViewModel(
                walletRepository = walletRepository,
                preferencesStore = preferencesStore
            ) as T
        }
    }
}
