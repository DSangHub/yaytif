package com.yaytif.app.ui.onboarding

import com.yaytif.app.data.preferences.UserPreferences
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.YaytifApiFactory
import com.yaytif.app.data.remote.YaytifApiService
import com.yaytif.app.domain.PhoneHashResult
import com.yaytif.app.domain.PhoneHasher
import com.yaytif.app.permissions.PermissionChecker
import com.yaytif.app.permissions.PermissionStatus
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

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: YaytifApiService
    private lateinit var preferencesStore: FakeOnboardingPreferencesStore
    private lateinit var permissionChecker: MutablePermissionChecker

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockWebServer = MockWebServer().apply { start() }
        apiService = YaytifApiFactory.createService(baseUrl = mockWebServer.url("/").toString())
        preferencesStore = FakeOnboardingPreferencesStore()
        permissionChecker = MutablePermissionChecker(
            PermissionStatus(
                notificationListenerGranted = false,
                overlayGranted = false,
                postNotificationsGranted = false,
                postNotificationsRequired = true
            )
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun registerAndContinue_sendsSha256HashNeverRawPhone_persistsState_andAdvancesToPermissions() = runBlocking {
        val rawPhone = "11 98765-4321"
        val expectedHash = (PhoneHasher.normalizeAndHash(rawPhone, "BR") as PhoneHashResult.Success).phoneHash

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "usr_br_001",
                      "phoneHash": "$expectedHash",
                      "countryCode": "BR",
                      "locale": "pt-BR",
                      "currency": "BRL",
                      "interests": ["ecommerce", "fintech"],
                      "walletBalance": "0.0000",
                      "dailyTimeEarned": "0.0000",
                      "dailyCapResetAt": "2026-11-28T00:00:00.000Z",
                      "createdAt": "2026-11-27T00:00:00.000Z"
                    }
                    """.trimIndent()
                )
        )

        var syncedCountry: String? = null
        val viewModel = OnboardingViewModel(
            apiService = apiService,
            preferencesStore = preferencesStore,
            permissionChecker = permissionChecker,
            onRegisteredSync = { syncedCountry = it }
        )

        viewModel.selectLocale("pt-BR")
        viewModel.selectMarket("BR")
        viewModel.nextFromMarket()
        assertEquals(OnboardingStep.INTERESTS, viewModel.uiState.value.currentStep)

        viewModel.nextFromInterests()
        assertEquals(OnboardingStep.PHONE_REGISTRATION, viewModel.uiState.value.currentStep)

        viewModel.updatePhoneInput(rawPhone)
        viewModel.registerAndContinue()
        withTimeout(5_000L) {
            viewModel.uiState.first { !it.isRegistering }
        }

        val request = mockWebServer.takeRequest()
        assertEquals("/api/v1/users/register", request.path)
        val requestBody = request.body.readUtf8()
        assertTrue(requestBody.contains(expectedHash))
        assertFalse("Raw phone number must never be sent to backend", requestBody.contains("98765"))
        assertFalse("Raw phone number must never be sent to backend", requestBody.contains("+5511"))

        val uiState = viewModel.uiState.value
        assertEquals(OnboardingStep.PERMISSIONS, uiState.currentStep)
        assertEquals("usr_br_001", uiState.registeredUserId)
        assertEquals("", uiState.phoneInput)
        assertNull(uiState.error)
        assertEquals("BR", syncedCountry)

        val savedPrefs = preferencesStore.getSnapshot()
        assertEquals("usr_br_001", savedPrefs.userId)
        assertEquals(expectedHash, savedPrefs.phoneHash)
        assertEquals("BR", savedPrefs.countryCode)
        assertEquals("pt-BR", savedPrefs.localeTag)
        assertEquals("BRL", savedPrefs.currency)
        assertFalse(savedPrefs.onboardingCompleted)

        viewModel.completeOnboarding()
        assertTrue(preferencesStore.getSnapshot().onboardingCompleted)
        assertTrue(viewModel.uiState.value.isCompleted)
    }

    @Test
    fun validation_blocksEmptyInterestsAndInvalidPhoneWithoutNetworkCall() = runBlocking {
        val viewModel = OnboardingViewModel(
            apiService = apiService,
            preferencesStore = preferencesStore,
            permissionChecker = permissionChecker
        )

        // Deselect all default interests
        viewModel.nextFromMarket()
        viewModel.uiState.value.selectedInterests.toList().forEach { viewModel.toggleInterest(it) }
        viewModel.nextFromInterests()

        assertEquals(OnboardingStep.INTERESTS, viewModel.uiState.value.currentStep)
        assertEquals(OnboardingError.SelectAtLeastOneInterest, viewModel.uiState.value.error)

        // Select one interest and test phone validation
        viewModel.toggleInterest("telecom")
        viewModel.nextFromInterests()
        assertEquals(OnboardingStep.PHONE_REGISTRATION, viewModel.uiState.value.currentStep)

        viewModel.registerAndContinue()
        assertEquals(OnboardingError.PhoneEmpty, viewModel.uiState.value.error)

        viewModel.updatePhoneInput("123")
        viewModel.registerAndContinue()
        assertEquals(OnboardingError.PhoneTooShort, viewModel.uiState.value.error)

        viewModel.updatePhoneInput("not-a-phone")
        viewModel.registerAndContinue()
        assertEquals(OnboardingError.PhoneInvalid, viewModel.uiState.value.error)

        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun registerAndContinue_surfacesHttpErrorAndAllowsCleanRetry() = runBlocking {
        val rawPhone = "0803 123 4567"
        val expectedHash = (PhoneHasher.normalizeAndHash(rawPhone, "NG") as PhoneHashResult.Success).phoneHash

        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "usr_ng_009",
                      "phoneHash": "$expectedHash",
                      "countryCode": "NG",
                      "locale": "en",
                      "currency": "NGN",
                      "interests": ["ecommerce", "fintech"],
                      "walletBalance": "0.0000",
                      "dailyTimeEarned": "0.0000",
                      "dailyCapResetAt": "2026-11-28T00:00:00.000Z",
                      "createdAt": "2026-11-27T00:00:00.000Z"
                    }
                    """.trimIndent()
                )
        )

        val viewModel = OnboardingViewModel(
            apiService = apiService,
            preferencesStore = preferencesStore,
            permissionChecker = permissionChecker
        )

        viewModel.selectMarket("NG")
        viewModel.nextFromMarket()
        viewModel.nextFromInterests()
        viewModel.updatePhoneInput(rawPhone)

        // First attempt hits 503
        viewModel.registerAndContinue()
        withTimeout(5_000L) {
            viewModel.uiState.first { !it.isRegistering }
        }
        assertEquals(OnboardingStep.PHONE_REGISTRATION, viewModel.uiState.value.currentStep)
        assertEquals(OnboardingError.RegistrationFailed(statusCode = 503), viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isRegistering)

        // Retry succeeds
        viewModel.registerAndContinue()
        withTimeout(5_000L) {
            viewModel.uiState.first { !it.isRegistering }
        }
        assertEquals(OnboardingStep.PERMISSIONS, viewModel.uiState.value.currentStep)
        assertNull(viewModel.uiState.value.error)
        assertEquals("usr_ng_009", viewModel.uiState.value.registeredUserId)
    }

    @Test
    fun refreshPermissions_updatesLiveStatusWhenUserReturnsFromSettings() = runBlocking {
        val viewModel = OnboardingViewModel(
            apiService = apiService,
            preferencesStore = preferencesStore,
            permissionChecker = permissionChecker
        )

        assertFalse(viewModel.uiState.value.permissionStatus.notificationListenerGranted)
        assertFalse(viewModel.uiState.value.permissionStatus.overlayGranted)

        permissionChecker.current = PermissionStatus(
            notificationListenerGranted = true,
            overlayGranted = true,
            postNotificationsGranted = true,
            postNotificationsRequired = true
        )
        viewModel.refreshPermissions()

        val refreshed = viewModel.uiState.value.permissionStatus
        assertTrue(refreshed.notificationListenerGranted)
        assertTrue(refreshed.overlayGranted)
        assertTrue(refreshed.allRecommendedGranted)
    }

    @Test
    fun init_resumesAtPermissionsStepWhenAlreadyRegisteredButNotCompleted() = runBlocking {
        preferencesStore.saveRegistration(
            userId = "usr_ke_007",
            phoneHash = "a".repeat(64),
            countryCode = "KE",
            localeTag = "sw",
            currency = "KES",
            interests = setOf("fintech"),
            onboardingCompleted = false
        )

        val viewModel = OnboardingViewModel(
            apiService = apiService,
            preferencesStore = preferencesStore,
            permissionChecker = permissionChecker
        )

        assertEquals(OnboardingStep.PERMISSIONS, viewModel.uiState.value.currentStep)
        assertEquals("usr_ke_007", viewModel.uiState.value.registeredUserId)
        assertEquals("KE", viewModel.uiState.value.selectedCountryCode)
        assertEquals("KES", viewModel.uiState.value.selectedCurrency)
        assertNotNull(viewModel.uiState.value.registeredUserId)
    }

    private class MutablePermissionChecker(
        var current: PermissionStatus
    ) : PermissionChecker {
        override fun checkStatus(): PermissionStatus = current
    }

    private class FakeOnboardingPreferencesStore(
        initial: UserPreferences = UserPreferences()
    ) : UserPreferencesStore {
        private val state = MutableStateFlow(initial)
        override val preferencesFlow: Flow<UserPreferences> = state

        override suspend fun getSnapshot(): UserPreferences = state.value

        override suspend fun setUserId(userId: String) {
            state.value = state.value.copy(userId = userId)
        }

        override suspend fun saveRegistration(
            userId: String,
            phoneHash: String,
            countryCode: String,
            localeTag: String,
            currency: String,
            interests: Set<String>,
            onboardingCompleted: Boolean
        ) {
            state.value = state.value.copy(
                userId = userId,
                phoneHash = phoneHash,
                countryCode = countryCode,
                localeTag = localeTag,
                currency = currency,
                interests = interests,
                onboardingCompleted = onboardingCompleted
            )
        }

        override suspend fun setOnboardingCompleted(completed: Boolean) {
            state.value = state.value.copy(onboardingCompleted = completed)
        }

        override suspend fun setLocaleTag(localeTag: String) {
            state.value = state.value.copy(localeTag = localeTag)
        }

        override suspend fun setMarket(countryCode: String, currency: String) {
            state.value = state.value.copy(countryCode = countryCode, currency = currency)
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
}
