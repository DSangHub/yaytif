package com.yaytif.app.data.repository

import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.remote.YaytifApiService
import com.yaytif.app.data.remote.dto.CreatePayoutRequest
import com.yaytif.app.data.remote.dto.CreatePayoutResponseDto
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.data.remote.dto.PayoutRequestDto
import com.yaytif.app.data.remote.dto.PayoutStatusDto
import com.yaytif.app.data.remote.dto.RegisterUserRequest
import com.yaytif.app.data.remote.dto.UpdateInterestsRequest
import com.yaytif.app.data.remote.dto.UserProfileDto
import com.yaytif.app.data.remote.dto.WalletLedgerResponseDto
import retrofit2.HttpException
import java.io.IOException
import java.math.BigDecimal

sealed interface PayoutSubmissionResult {
    data class Success(val response: CreatePayoutResponseDto) : PayoutSubmissionResult
    data object InsufficientFunds : PayoutSubmissionResult
    data object ValidationError : PayoutSubmissionResult
    data object UserNotFound : PayoutSubmissionResult
    data class NetworkError(val detail: String? = null) : PayoutSubmissionResult
}

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

    suspend fun fetchWalletLedger(
        userId: String,
        cursor: String? = null,
        limit: Int = DEFAULT_LEDGER_PAGE_SIZE
    ): WalletLedgerResponseDto {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        return apiService.getWalletLedger(
            userId = userId,
            cursor = cursor,
            limit = limit
        )
    }

    suspend fun fetchPayouts(
        userId: String,
        status: PayoutStatusDto? = null,
        limit: Int = DEFAULT_PAYOUTS_LIMIT
    ): List<PayoutRequestDto> {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        return apiService.getPayouts(
            userId = userId,
            status = status,
            limit = limit
        ).payouts
    }

    suspend fun requestPayout(
        userId: String,
        rail: PayoutRailDto,
        destination: String,
        amount: BigDecimal
    ): CreatePayoutResponseDto {
        val trimmedDestination = destination.trim()
        require(userId.isNotBlank()) { "userId must not be blank" }
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

    suspend fun submitPayout(
        userId: String,
        rail: PayoutRailDto,
        destination: String,
        amount: BigDecimal
    ): PayoutSubmissionResult {
        return try {
            val response = requestPayout(
                userId = userId,
                rail = rail,
                destination = destination,
                amount = amount
            )
            PayoutSubmissionResult.Success(response)
        } catch (http: HttpException) {
            when (http.code()) {
                409 -> PayoutSubmissionResult.InsufficientFunds
                400, 422 -> PayoutSubmissionResult.ValidationError
                404 -> PayoutSubmissionResult.UserNotFound
                else -> PayoutSubmissionResult.NetworkError("HTTP ${http.code()}")
            }
        } catch (io: IOException) {
            PayoutSubmissionResult.NetworkError(io.message)
        }
    }

    companion object {
        const val DEFAULT_LEDGER_PAGE_SIZE = 20
        const val DEFAULT_PAYOUTS_LIMIT = 50
        private const val MAX_PAGE_SIZE = 100
    }
}
