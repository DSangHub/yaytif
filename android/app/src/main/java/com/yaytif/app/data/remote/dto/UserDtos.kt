package com.yaytif.app.data.remote.dto

import com.yaytif.app.data.remote.BigDecimalSerializer
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class RegisterUserRequest(
    val phoneHash: String,
    val countryCode: String,
    val locale: String,
    val currency: String,
    val interests: List<String> = emptyList()
)

@Serializable
data class UpdateInterestsRequest(
    val interests: List<String>
)

@Serializable
data class UserProfileDto(
    val id: String,
    val phoneHash: String,
    val countryCode: String,
    val locale: String,
    val currency: String,
    val interests: List<String> = emptyList(),
    @Serializable(with = BigDecimalSerializer::class)
    val walletBalance: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class)
    val dailyTimeEarned: BigDecimal,
    val dailyCapResetAt: String,
    val createdAt: String
)
