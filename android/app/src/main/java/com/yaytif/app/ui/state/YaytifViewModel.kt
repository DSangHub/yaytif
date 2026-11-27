package com.yaytif.app.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yaytif.app.data.local.entity.LocalNotificationEntity
import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import com.yaytif.app.data.preferences.UserPreferences
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.dto.LedgerEntryDto
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.data.remote.dto.PayoutRequestDto
import com.yaytif.app.data.repository.LocalNotificationRepository
import com.yaytif.app.data.repository.SponsoredAlertRepository
import com.yaytif.app.data.repository.WalletRepository
import com.yaytif.app.domain.MarketConfig
import com.yaytif.app.domain.MoneyFormatter
import com.yaytif.app.domain.PayoutValidationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class WalletUiState(
    val walletBalance: BigDecimal = BigDecimal.ZERO,
    val dailyTimeEarned: BigDecimal = BigDecimal.ZERO,
    val ledgerEntries: List<LedgerEntryDto> = emptyList(),
    val payouts: List<PayoutRequestDto> = emptyList(),
    val isLoading: Boolean = false,
    val payoutFeedback: PayoutFeedback? = null
)

sealed interface PayoutFeedback {
    data object Submitted : PayoutFeedback
    data object MissingAccount : PayoutFeedback
    data object MissingDestination : PayoutFeedback
    data object InvalidAmount : PayoutFeedback
    data class BelowMinimum(val formattedMinimum: String) : PayoutFeedback
    data object InsufficientBalance : PayoutFeedback
    data class NetworkError(val message: String) : PayoutFeedback
}

class YaytifViewModel(
    private val preferencesStore: UserPreferencesStore,
    private val sponsoredAlertRepository: SponsoredAlertRepository,
    private val localNotificationRepository: LocalNotificationRepository,
    private val walletRepository: WalletRepository,
    private val enqueueImmediateSync: (countryCode: String?, category: String?) -> Unit
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesStore.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = UserPreferences()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val sponsoredAlerts: StateFlow<List<SponsoredAlertEntity>> = preferences
        .map { it.selectedCategory }
        .distinctUntilChanged()
        .flatMapLatest { category ->
            sponsoredAlertRepository.observeActiveAlerts(category)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    val localNotifications: StateFlow<List<LocalNotificationEntity>> = localNotificationRepository
        .observeActiveNotifications()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    val unreadVaultCount: StateFlow<Int> = localNotificationRepository
        .observeUnreadCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = 0
        )

    private val _isSyncingAlerts = MutableStateFlow(false)
    val isSyncingAlerts: StateFlow<Boolean> = _isSyncingAlerts.asStateFlow()

    private val _walletState = MutableStateFlow(WalletUiState())
    val walletState: StateFlow<WalletUiState> = _walletState.asStateFlow()

    fun selectCategory(category: String?) {
        viewModelScope.launch {
            preferencesStore.setSelectedCategory(category)
        }
    }

    fun triggerAlertSync() {
        val snapshot = preferences.value
        enqueueImmediateSync(snapshot.countryCode, snapshot.selectedCategory)
        viewModelScope.launch {
            _isSyncingAlerts.value = true
            runCatching {
                sponsoredAlertRepository.syncSponsoredAlerts(
                    userId = snapshot.userId,
                    countryCode = snapshot.countryCode,
                    category = snapshot.selectedCategory
                )
            }
            _isSyncingAlerts.value = false
        }
    }

    fun markNotificationRead(id: Long) {
        viewModelScope.launch {
            localNotificationRepository.markAsRead(id)
        }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            localNotificationRepository.markAllAsRead()
        }
    }

    fun archiveNotification(id: Long) {
        viewModelScope.launch {
            localNotificationRepository.archive(id)
        }
    }

    fun clearLocalVault() {
        viewModelScope.launch {
            localNotificationRepository.clearAll()
        }
    }

    fun updateLocale(localeTag: String) {
        viewModelScope.launch {
            preferencesStore.setLocaleTag(localeTag)
        }
    }

    fun updateMarket(countryCode: String) {
        val market = MarketConfig.marketForCountry(countryCode)
        viewModelScope.launch {
            preferencesStore.setMarket(market.countryCode, market.currency)
            enqueueImmediateSync(market.countryCode, preferences.value.selectedCategory)
        }
    }

    fun toggleInterest(category: String) {
        val current = preferences.value.interests.toMutableSet()
        if (!current.add(category)) {
            current.remove(category)
        }
        viewModelScope.launch {
            preferencesStore.setInterests(current)
            val userId = preferences.value.userId
            if (!userId.isNullOrBlank()) {
                runCatching { walletRepository.syncInterests(userId, current) }
            }
        }
    }

    fun refreshWallet() {
        val userId = preferences.value.userId ?: return
        viewModelScope.launch {
            _walletState.update { it.copy(isLoading = true) }
            runCatching {
                val ledger = walletRepository.fetchWalletLedger(userId)
                val payouts = walletRepository.fetchPayouts(userId)
                _walletState.update {
                    it.copy(
                        walletBalance = ledger.walletBalance,
                        dailyTimeEarned = ledger.dailyTimeEarned,
                        ledgerEntries = ledger.entries,
                        payouts = payouts,
                        isLoading = false
                    )
                }
            }.onFailure {
                _walletState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun clearPayoutFeedback() {
        _walletState.update { it.copy(payoutFeedback = null) }
    }

    fun submitPayout(rail: PayoutRailDto, destination: String, rawAmount: String) {
        val prefs = preferences.value
        val market = MarketConfig.marketForCountry(prefs.countryCode)
        val trimmedDestination = destination.trim()

        if (trimmedDestination.isEmpty()) {
            _walletState.update { it.copy(payoutFeedback = PayoutFeedback.MissingDestination) }
            return
        }

        val validation = MoneyFormatter.validatePayoutAmount(
            rawAmount = rawAmount,
            availableBalance = _walletState.value.walletBalance,
            minimumAmount = market.minimumPayoutAmount
        )

        val validatedAmount = when (validation) {
            is PayoutValidationResult.Valid -> validation.amount
            PayoutValidationResult.InvalidFormat,
            PayoutValidationResult.TooManyDecimals,
            PayoutValidationResult.MustBePositive -> {
                _walletState.update { it.copy(payoutFeedback = PayoutFeedback.InvalidAmount) }
                return
            }
            is PayoutValidationResult.BelowMinimum -> {
                val formattedMin = MoneyFormatter.formatAmount(validation.minimum, prefs.currency)
                _walletState.update { it.copy(payoutFeedback = PayoutFeedback.BelowMinimum(formattedMin)) }
                return
            }
            is PayoutValidationResult.InsufficientBalance -> {
                _walletState.update { it.copy(payoutFeedback = PayoutFeedback.InsufficientBalance) }
                return
            }
        }

        val userId = prefs.userId
        if (userId.isNullOrBlank()) {
            _walletState.update { it.copy(payoutFeedback = PayoutFeedback.MissingAccount) }
            return
        }

        viewModelScope.launch {
            _walletState.update { it.copy(isLoading = true, payoutFeedback = null) }
            runCatching {
                val created = walletRepository.requestPayout(
                    userId = userId,
                    rail = rail,
                    destination = trimmedDestination,
                    amount = validatedAmount
                )
                _walletState.update { current ->
                    current.copy(
                        walletBalance = current.walletBalance.subtract(validatedAmount).max(BigDecimal.ZERO),
                        payouts = listOf(created) + current.payouts,
                        isLoading = false,
                        payoutFeedback = PayoutFeedback.Submitted
                    )
                }
            }.onFailure { err ->
                _walletState.update {
                    it.copy(
                        isLoading = false,
                        payoutFeedback = PayoutFeedback.NetworkError(err.message ?: "Request failed")
                    )
                }
            }
        }
    }

    class Factory(
        private val preferencesStore: UserPreferencesStore,
        private val sponsoredAlertRepository: SponsoredAlertRepository,
        private val localNotificationRepository: LocalNotificationRepository,
        private val walletRepository: WalletRepository,
        private val enqueueImmediateSync: (countryCode: String?, category: String?) -> Unit
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(YaytifViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return YaytifViewModel(
                preferencesStore = preferencesStore,
                sponsoredAlertRepository = sponsoredAlertRepository,
                localNotificationRepository = localNotificationRepository,
                walletRepository = walletRepository,
                enqueueImmediateSync = enqueueImmediateSync
            ) as T
        }
    }
}
