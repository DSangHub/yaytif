package com.yaytif.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yaytif.app.domain.MarketConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.yaytifPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "yaytif_user_preferences"
)

data class UserPreferences(
    val userId: String? = null,
    val phoneHash: String? = null,
    val countryCode: String = MarketConfig.DEFAULT_COUNTRY_CODE,
    val localeTag: String = MarketConfig.DEFAULT_LOCALE_TAG,
    val currency: String = MarketConfig.DEFAULT_CURRENCY,
    val interests: Set<String> = MarketConfig.DEFAULT_INTERESTS,
    val selectedCategory: String? = null,
    val lastAlertSyncEpochMs: Long? = null,
    val onboardingCompleted: Boolean = false
)

interface UserPreferencesStore {
    val preferencesFlow: Flow<UserPreferences>
    suspend fun getSnapshot(): UserPreferences
    suspend fun setUserId(userId: String)
    suspend fun saveRegistration(
        userId: String,
        phoneHash: String,
        countryCode: String,
        localeTag: String,
        currency: String,
        interests: Set<String>,
        onboardingCompleted: Boolean = false
    )
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setMarket(countryCode: String, currency: String)
    suspend fun setLocaleTag(localeTag: String)
    suspend fun setInterests(interests: Set<String>)
    suspend fun setSelectedCategory(category: String?)
    suspend fun setLastAlertSyncEpochMs(epochMs: Long)
}

class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : UserPreferencesStore {

    constructor(context: Context) : this(context.applicationContext.yaytifPreferencesDataStore)

    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val PHONE_HASH = stringPreferencesKey("phone_hash")
        val COUNTRY_CODE = stringPreferencesKey("country_code")
        val LOCALE_TAG = stringPreferencesKey("locale_tag")
        val CURRENCY = stringPreferencesKey("currency")
        val INTERESTS = stringSetPreferencesKey("interests")
        val SELECTED_CATEGORY = stringPreferencesKey("selected_category")
        val LAST_ALERT_SYNC_EPOCH_MS = longPreferencesKey("last_alert_sync_epoch_ms")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    override val preferencesFlow: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val countryCode = prefs[Keys.COUNTRY_CODE] ?: MarketConfig.DEFAULT_COUNTRY_CODE
            val defaultCurrency = MarketConfig.marketForCountry(countryCode).currency
            UserPreferences(
                userId = prefs[Keys.USER_ID],
                phoneHash = prefs[Keys.PHONE_HASH],
                countryCode = countryCode,
                localeTag = prefs[Keys.LOCALE_TAG] ?: MarketConfig.DEFAULT_LOCALE_TAG,
                currency = prefs[Keys.CURRENCY] ?: defaultCurrency,
                interests = prefs[Keys.INTERESTS] ?: MarketConfig.DEFAULT_INTERESTS,
                selectedCategory = prefs[Keys.SELECTED_CATEGORY],
                lastAlertSyncEpochMs = prefs[Keys.LAST_ALERT_SYNC_EPOCH_MS],
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
            )
        }

    override suspend fun getSnapshot(): UserPreferences = preferencesFlow.first()

    override suspend fun setUserId(userId: String) {
        dataStore.edit { prefs ->
            prefs[Keys.USER_ID] = userId.trim()
        }
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
        dataStore.edit { prefs ->
            prefs[Keys.USER_ID] = userId.trim()
            prefs[Keys.PHONE_HASH] = phoneHash.trim()
            prefs[Keys.COUNTRY_CODE] = countryCode.uppercase()
            prefs[Keys.LOCALE_TAG] = localeTag
            prefs[Keys.CURRENCY] = currency.uppercase()
            prefs[Keys.INTERESTS] = interests
            prefs[Keys.ONBOARDING_COMPLETED] = onboardingCompleted
        }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = completed
        }
    }

    override suspend fun setMarket(countryCode: String, currency: String) {
        dataStore.edit { prefs ->
            prefs[Keys.COUNTRY_CODE] = countryCode.uppercase()
            prefs[Keys.CURRENCY] = currency.uppercase()
        }
    }

    override suspend fun setLocaleTag(localeTag: String) {
        dataStore.edit { prefs ->
            prefs[Keys.LOCALE_TAG] = localeTag
        }
    }

    override suspend fun setInterests(interests: Set<String>) {
        dataStore.edit { prefs ->
            prefs[Keys.INTERESTS] = interests
        }
    }

    override suspend fun setSelectedCategory(category: String?) {
        dataStore.edit { prefs ->
            if (category.isNullOrBlank()) {
                prefs.remove(Keys.SELECTED_CATEGORY)
            } else {
                prefs[Keys.SELECTED_CATEGORY] = category
            }
        }
    }

    override suspend fun setLastAlertSyncEpochMs(epochMs: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_ALERT_SYNC_EPOCH_MS] = epochMs
        }
    }
}
