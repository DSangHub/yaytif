package com.yaytif.app.data.repository

import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.YaytifApiService
import com.yaytif.app.data.remote.dto.CreatePayoutRequest
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.data.remote.dto.PayoutRequestDto
import com.yaytif.app.data.remote.dto.RegisterUserRequest
import com.yaytif.app.data.remote.dto.UpdateInterestsRequest
import com.yaytif.app.data.remote.dto.UserProfileDto
import com.yaytif.app.data.remote.dto.WalletLedgerResponseDto
import java.math.BigDecimal

class WalletRepository(
    private val apiService: YaytifApiService,
    private val preferencesStore: UserPreferencesStore
) {

    suspend fun registerUser(
        phoneHash: String,
        countryCode: String,
        locale: String,
        currency: String,
        interests: List<String>
    ): UserProfileDto {
        val profile = apiService.registerUser(
            RegisterUserRequest(
                phoneHash = phoneHash,
                countryCode = countryCode,
                locale = locale,
                currency = currency,
                interests = interests
            )
        )
        preferencesStore.setUserId(profile.id)
        preferencesStore.setMarket(profile.countryCode, profile.currency)
        preferencesStore.setInterests(profile.interests.toSet())
        return profile
    }

    suspend fun fetchUserProfile(userId: String): UserProfileDto {
        return apiService.getUser(userId)
    }

    suspend fun syncInterests(userId: String, interests: Set<String>): UserProfileDto {
        val updated = apiService.updateUserInterests(
            userId = userId,
            request = UpdateInterestsRequest(interests = interests.sorted())
        )
        preferencesStore.setInterests(updated.interests.toSet())
        return updated
    }

    suspend fun fetchWalletLedger(userId: String): WalletLedgerResponseDto {
        return apiService.getWalletLedger(userId)
    }

    suspend fun fetchPayouts(userId: String): List<PayoutRequestDto> {
        return apiService.getPayouts(userId).payouts
    }

    suspend fun requestPayout(
        userId: String,
        rail: PayoutRailDto,
        destination: String,
        amount: BigDecimal
    ): PayoutRequestDto {
        val trimmedDestination = destination.trim()
        require(trimmedDestination.isNotEmpty()) { "Payout destination must not be blank" }
        require(amount.compareTo(BigDecimal.ZERO) > 0) { "Payout amount must be strictly positive" }

        return apiService.createPayout(
            CreatePayoutRequest(
                userId = userId,
                rail = rail,
                destination = trimmedDestination,
                amount = amount
            )
        )
    }
}
