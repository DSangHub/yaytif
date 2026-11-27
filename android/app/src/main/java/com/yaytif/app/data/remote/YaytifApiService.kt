package com.yaytif.app.data.remote

import com.yaytif.app.data.remote.dto.AlertsResponseDto
import com.yaytif.app.data.remote.dto.CreatePayoutRequest
import com.yaytif.app.data.remote.dto.CreatePayoutResponseDto
import com.yaytif.app.data.remote.dto.PayoutListResponseDto
import com.yaytif.app.data.remote.dto.PayoutStatusDto
import com.yaytif.app.data.remote.dto.RegisterUserRequest
import com.yaytif.app.data.remote.dto.SessionHeartbeatRequest
import com.yaytif.app.data.remote.dto.SessionHeartbeatResponse
import com.yaytif.app.data.remote.dto.StartSessionRequest
import com.yaytif.app.data.remote.dto.StartSessionResponse
import com.yaytif.app.data.remote.dto.UpdateInterestsRequest
import com.yaytif.app.data.remote.dto.UserProfileDto
import com.yaytif.app.data.remote.dto.WalletLedgerResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit contract aligned with Yaytif backend /api/v1 endpoints.
 *
 * PRIVACY INVARIANT (.cursorrules):
 * This API only communicates user profile metadata, interest tags, sponsored alert
 * campaigns, verified attention-time heartbeats, affiliate sessions, and wallet payouts.
 * Personal intercepted notification contents are strictly prohibited here.
 */
interface YaytifApiService {

    @POST("api/v1/users/register")
    suspend fun registerUser(
        @Body request: RegisterUserRequest
    ): UserProfileDto

    @GET("api/v1/users/{userId}")
    suspend fun getUser(
        @Path("userId") userId: String
    ): UserProfileDto

    @PATCH("api/v1/users/{userId}/interests")
    suspend fun updateUserInterests(
        @Path("userId") userId: String,
        @Body request: UpdateInterestsRequest
    ): UserProfileDto

    @GET("api/v1/alerts")
    suspend fun getAlerts(
        @Query("userId") userId: String? = null,
        @Query("countryCode") countryCode: String? = null,
        @Query("category") category: String? = null
    ): AlertsResponseDto

    @POST("api/v1/sessions/start")
    suspend fun startSession(
        @Body request: StartSessionRequest
    ): StartSessionResponse

    @POST("api/v1/sessions/{sessionId}/heartbeat")
    suspend fun sendSessionHeartbeat(
        @Path("sessionId") sessionId: String,
        @Body request: SessionHeartbeatRequest
    ): SessionHeartbeatResponse

    @GET("api/v1/wallet/{userId}/ledger")
    suspend fun getWalletLedger(
        @Path("userId") userId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null
    ): WalletLedgerResponseDto

    @POST("api/v1/payouts")
    suspend fun createPayout(
        @Body request: CreatePayoutRequest
    ): CreatePayoutResponseDto

    @GET("api/v1/payouts")
    suspend fun getPayouts(
        @Query("userId") userId: String,
        @Query("status") status: PayoutStatusDto? = null,
        @Query("limit") limit: Int? = null
    ): PayoutListResponseDto
}
