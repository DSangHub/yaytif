package com.yaytif.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.YaytifApiService
import com.yaytif.app.data.remote.dto.RegisterUserRequest
import com.yaytif.app.domain.MarketConfig
import com.yaytif.app.domain.PhoneHashResult
import com.yaytif.app.domain.PhoneHasher
import com.yaytif.app.domain.PhoneNormalizationResult
import com.yaytif.app.permissions.PermissionChecker
import com.yaytif.app.permissions.PermissionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

enum class OnboardingStep {
    MARKET,
    INTERESTS,
    PHONE_REGISTRATION,
    PERMISSIONS
}

sealed interface OnboardingError {
    data object SelectAtLeastOneInterest : OnboardingError
    data object PhoneEmpty : OnboardingError
    data object PhoneInvalid : OnboardingError
    data object PhoneTooShort : OnboardingError
    data object PhoneTooLong : OnboardingError
    data class RegistrationFailed(val statusCode: Int? = null) : OnboardingError
}

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.MARKET,
    val selectedLocaleTag: String = MarketConfig.DEFAULT_LOCALE_TAG,
    val selectedCountryCode: String = MarketConfig.DEFAULT_COUNTRY_CODE,
    val selectedCurrency: String = MarketConfig.DEFAULT_CURRENCY,
    val selectedInterests: Set<String> = MarketConfig.DEFAULT_INTERESTS,
    val phoneInput: String = "",
    val isRegistering: Boolean = false,
    val registeredUserId: String? = null,
    val error: OnboardingError? = null,
    val permissionStatus: PermissionStatus = PermissionStatus(),
    val isCompleted: Boolean = false
)

class OnboardingViewModel(
    private val apiService: YaytifApiService,
    private val preferencesStore: UserPreferencesStore,
    private val permissionChecker: PermissionChecker,
    private val onRegisteredSync: (countryCode: String) -> Unit = {}
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OnboardingUiState(permissionStatus = permissionChecker.checkStatus())
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = preferencesStore.getSnapshot()
            val market = MarketConfig.marketForCountry(prefs.countryCode)
            val resumeAtPermissions = !prefs.userId.isNullOrBlank() && !prefs.onboardingCompleted
            _uiState.update { current ->
                current.copy(
                    currentStep = if (resumeAtPermissions) OnboardingStep.PERMISSIONS else current.currentStep,
                    selectedLocaleTag = prefs.localeTag,
                    selectedCountryCode = market.countryCode,
                    selectedCurrency = prefs.currency,
                    selectedInterests = prefs.interests.ifEmpty { MarketConfig.DEFAULT_INTERESTS },
                    registeredUserId = prefs.userId,
                    permissionStatus = permissionChecker.checkStatus(),
                    isCompleted = prefs.onboardingCompleted
                )
            }
        }
    }

    fun selectLocale(localeTag: String) {
        _uiState.update { it.copy(selectedLocaleTag = localeTag, error = null) }
        viewModelScope.launch {
            preferencesStore.setLocaleTag(localeTag)
        }
    }

    fun selectMarket(countryCode: String) {
        val market = MarketConfig.marketForCountry(countryCode)
        _uiState.update {
            it.copy(
                selectedCountryCode = market.countryCode,
                selectedCurrency = market.currency,
                error = null
            )
        }
    }

    fun toggleInterest(category: String) {
        _uiState.update { current ->
            val updated = current.selectedInterests.toMutableSet()
            if (!updated.add(category)) {
                updated.remove(category)
            }
            current.copy(selectedInterests = updated, error = null)
        }
    }

    fun updatePhoneInput(input: String) {
        _uiState.update { it.copy(phoneInput = input, error = null) }
    }

    fun nextFromMarket() {
        _uiState.update { it.copy(currentStep = OnboardingStep.INTERESTS, error = null) }
    }

    fun nextFromInterests() {
        if (_uiState.value.selectedInterests.isEmpty()) {
            _uiState.update { it.copy(error = OnboardingError.SelectAtLeastOneInterest) }
            return
        }
        val nextStep = if (_uiState.value.registeredUserId.isNullOrBlank()) {
            OnboardingStep.PHONE_REGISTRATION
        } else {
            OnboardingStep.PERMISSIONS
        }
        _uiState.update { it.copy(currentStep = nextStep, error = null) }
    }

    fun goBack() {
        _uiState.update { current ->
            val previous = when (current.currentStep) {
                OnboardingStep.MARKET -> OnboardingStep.MARKET
                OnboardingStep.INTERESTS -> OnboardingStep.MARKET
                OnboardingStep.PHONE_REGISTRATION -> OnboardingStep.INTERESTS
                OnboardingStep.PERMISSIONS -> if (current.registeredUserId.isNullOrBlank()) {
                    OnboardingStep.PHONE_REGISTRATION
                } else {
                    OnboardingStep.INTERESTS
                }
            }
            current.copy(currentStep = previous, error = null)
        }
    }

    fun registerAndContinue() {
        val snapshot = _uiState.value
        if (snapshot.isRegistering) return

        if (snapshot.selectedInterests.isEmpty()) {
            _uiState.update { it.copy(error = OnboardingError.SelectAtLeastOneInterest) }
            return
        }

        val phoneHash = when (val hashed = PhoneHasher.normalizeAndHash(snapshot.phoneInput, snapshot.selectedCountryCode)) {
            is PhoneHashResult.Success -> hashed.phoneHash
            is PhoneHashResult.Invalid -> {
                val mappedError = when (hashed.reason) {
                    PhoneNormalizationResult.Empty -> OnboardingError.PhoneEmpty
                    PhoneNormalizationResult.TooShort -> OnboardingError.PhoneTooShort
                    PhoneNormalizationResult.TooLong -> OnboardingError.PhoneTooLong
                    PhoneNormalizationResult.InvalidCharacters,
                    is PhoneNormalizationResult.Valid -> OnboardingError.PhoneInvalid
                }
                _uiState.update { it.copy(error = mappedError) }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isRegistering = true, error = null) }
            try {
                val profile = apiService.registerUser(
                    RegisterUserRequest(
                        phoneHash = phoneHash,
                        countryCode = snapshot.selectedCountryCode,
                        locale = snapshot.selectedLocaleTag,
                        currency = snapshot.selectedCurrency,
                        interests = snapshot.selectedInterests.sorted()
                    )
                )

                preferencesStore.saveRegistration(
                    userId = profile.id,
                    phoneHash = profile.phoneHash,
                    countryCode = profile.countryCode,
                    localeTag = profile.locale,
                    currency = profile.currency,
                    interests = profile.interests.toSet(),
                    onboardingCompleted = false
                )

                onRegisteredSync(profile.countryCode)

                _uiState.update {
                    it.copy(
                        isRegistering = false,
                        phoneInput = "",
                        registeredUserId = profile.id,
                        currentStep = OnboardingStep.PERMISSIONS,
                        permissionStatus = permissionChecker.checkStatus(),
                        error = null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (httpEx: HttpException) {
                _uiState.update {
                    it.copy(
                        isRegistering = false,
                        error = OnboardingError.RegistrationFailed(statusCode = httpEx.code())
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isRegistering = false,
                        error = OnboardingError.RegistrationFailed(statusCode = null)
                    )
                }
            }
        }
    }

    fun refreshPermissions() {
        _uiState.update { it.copy(permissionStatus = permissionChecker.checkStatus()) }
    }

    fun completeOnboarding() {
        val snapshot = _uiState.value
        viewModelScope.launch {
            preferencesStore.setLocaleTag(snapshot.selectedLocaleTag)
            preferencesStore.setMarket(snapshot.selectedCountryCode, snapshot.selectedCurrency)
            if (snapshot.selectedInterests.isNotEmpty()) {
                preferencesStore.setInterests(snapshot.selectedInterests)
            }
            preferencesStore.setOnboardingCompleted(true)
            _uiState.update { it.copy(isCompleted = true) }
        }
    }

    class Factory(
        private val apiService: YaytifApiService,
        private val preferencesStore: UserPreferencesStore,
        private val permissionChecker: PermissionChecker,
        private val onRegisteredSync: (countryCode: String) -> Unit
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return OnboardingViewModel(
                apiService = apiService,
                preferencesStore = preferencesStore,
                permissionChecker = permissionChecker,
                onRegisteredSync = onRegisteredSync
            ) as T
        }
    }
}
