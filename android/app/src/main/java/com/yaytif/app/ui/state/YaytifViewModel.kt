package com.yaytif.app.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yaytif.app.data.local.entity.LocalNotificationEntity
import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import com.yaytif.app.data.preferences.UserPreferences
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.repository.LocalNotificationRepository
import com.yaytif.app.data.repository.SponsoredAlertRepository
import com.yaytif.app.data.repository.WalletRepository
import com.yaytif.app.domain.MarketConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
