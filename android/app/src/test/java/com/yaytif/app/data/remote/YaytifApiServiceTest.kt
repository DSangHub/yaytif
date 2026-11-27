package com.yaytif.app.data.remote

import com.yaytif.app.data.remote.dto.CreatePayoutRequest
import com.yaytif.app.data.remote.dto.LedgerEntryTypeDto
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.data.remote.dto.PayoutStatusDto
import com.yaytif.app.data.remote.dto.RegisterUserRequest
import com.yaytif.app.data.remote.dto.SessionHeartbeatRequest
import com.yaytif.app.data.remote.dto.StartSessionRequest
import com.yaytif.app.data.remote.dto.UpdateInterestsRequest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class YaytifApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var api: YaytifApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = YaytifApiFactory.createService(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `registerUser sends POST api v1 users register and parses BigDecimal balances`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa01",
                  "phoneHash": "abc123hash",
                  "countryCode": "BR",
                  "locale": "pt-BR",
                  "currency": "BRL",
                  "interests": ["food_delivery", "ecommerce"],
                  "walletBalance": "14.500000",
                  "dailyTimeEarned": "2.100000",
                  "dailyCapResetAt": "2026-11-27T00:00:00.000Z",
                  "createdAt": "2026-11-26T12:00:00.000Z"
                }
                """.trimIndent()
            )
        )

        val result = api.registerUser(
            RegisterUserRequest(
                phoneHash = "abc123hash",
                countryCode = "BR",
                locale = "pt-BR",
                currency = "BRL",
                interests = listOf("food_delivery", "ecommerce")
            )
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/users/register", recorded.path)
        assertTrue(recorded.body.readUtf8().contains(""""phoneHash":"abc123hash""""))
        assertEquals(BigDecimal("14.500000"), result.walletBalance)
        assertEquals("BRL", result.currency)
    }

    @Test
    fun `getUser and updateUserInterests hit expected paths`() = runTest {
        val userPayload = """
            {
              "id": "user-1",
              "phoneHash": "h1",
              "countryCode": "NG",
              "locale": "en",
              "currency": "NGN",
              "interests": ["fintech", "telecom"],
              "walletBalance": "4250.000000",
              "dailyTimeEarned": "630.000000",
              "dailyCapResetAt": "2026-11-27T00:00:00.000Z",
              "createdAt": "2026-11-26T12:00:00.000Z"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(userPayload))
        server.enqueue(MockResponse().setBody(userPayload))

        val fetched = api.getUser("user-1")
        assertEquals("GET", server.takeRequest().method)
        assertEquals(BigDecimal("4250.000000"), fetched.walletBalance)

        val updated = api.updateUserInterests("user-1", UpdateInterestsRequest(listOf("fintech", "telecom")))
        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertEquals("/api/v1/users/user-1/interests", patchReq.path)
        assertEquals(listOf("fintech", "telecom"), updated.interests)
    }

    @Test
    fun `getAlerts sends query parameters and parses sponsored alerts`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "alerts": [
                    {
                      "id": "11111111-1111-4111-8111-111111111101",
                      "advertiserName": "iFood Brasil",
                      "title": "R$ 25 Off no iFood",
                      "body": "Cashback via Pix",
                      "category": "food_delivery",
                      "countryCodes": ["BR"],
                      "priorityColor": "#10B981",
                      "payoutPerMinute": "0.180000",
                      "affiliateUrl": "https://track.yaytif.com/c/ifood-br?click_id={click_id}",
                      "commissionShare": "0.5000",
                      "budgetRemaining": "25000.000000",
                      "active": true,
                      "createdAt": "2026-11-26T12:00:00.000Z"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val response = api.getAlerts(userId = "u1", countryCode = "BR", category = "food_delivery")
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/alerts?userId=u1&countryCode=BR&category=food_delivery", recorded.path)
        assertEquals(1, response.alerts.size)
        assertEquals(BigDecimal("0.180000"), response.alerts.first().payoutPerMinute)
    }

    @Test
    fun `startSession and sendSessionHeartbeat match backend contract`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "sessionId": "sess-123",
                  "clickId": "click-456",
                  "resolvedAffiliateUrl": "https://track.yaytif.com/c/ifood-br?click_id=click-456"
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "sessionId": "sess-123",
                  "totalActiveSeconds": 30,
                  "creditedDelta": "0.090000",
                  "totalCreditedAmount": "0.090000",
                  "walletBalance": "14.590000",
                  "dailyCapReached": false
                }
                """.trimIndent()
            )
        )

        val started = api.startSession(StartSessionRequest(userId = "u1", alertId = "a1"))
        val startReq = server.takeRequest()
        assertEquals("POST", startReq.method)
        assertEquals("/api/v1/sessions/start", startReq.path)
        assertEquals("click-456", started.clickId)

        val beat = api.sendSessionHeartbeat("sess-123", SessionHeartbeatRequest(activeSecondsDelta = 30))
        val beatReq = server.takeRequest()
        assertEquals("POST", beatReq.method)
        assertEquals("/api/v1/sessions/sess-123/heartbeat", beatReq.path)
        assertEquals(BigDecimal("0.090000"), beat.creditedDelta)
        assertEquals(BigDecimal("14.590000"), beat.walletBalance)
    }

    @Test
    fun `wallet ledger and payout endpoints serialize decimal amounts as strings`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "wallet": {
                    "userId": "u1",
                    "currency": "KES",
                    "walletBalance": "380.000000",
                    "dailyTimeEarned": "54.000000",
                    "dailyCapResetAt": "2026-11-27T00:00:00.000Z"
                  },
                  "entries": [
                    {
                      "id": "led-1",
                      "userId": "u1",
                      "type": "ATTENTION_TIME",
                      "amount": "54.000000",
                      "currency": "KES",
                      "referenceId": "sess-1",
                      "createdAt": "2026-11-26T12:00:00.000Z"
                    }
                  ],
                  "pagination": {
                    "limit": 20,
                    "hasMore": true,
                    "nextCursor": "led-1"
                  }
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {
                  "payout": {
                    "id": "pay-1",
                    "userId": "u1",
                    "rail": "MPESA",
                    "destination": "+254700000003",
                    "amount": "200.000000",
                    "currency": "KES",
                    "status": "PENDING",
                    "createdAt": "2026-11-26T12:05:00.000Z"
                  },
                  "ledgerEntryId": "led-2",
                  "walletBalance": "180.000000"
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "payouts": [
                    {
                      "id": "pay-1",
                      "userId": "u1",
                      "rail": "MPESA",
                      "destination": "+254700000003",
                      "amount": "200.000000",
                      "currency": "KES",
                      "status": "PAID",
                      "externalTxId": "MPESA-TX-99",
                      "createdAt": "2026-11-26T12:05:00.000Z"
                    }
                  ],
                  "count": 1
                }
                """.trimIndent()
            )
        )

        val ledger = api.getWalletLedger("u1", cursor = "led-0", limit = 20)
        val ledgerReq = server.takeRequest()
        assertEquals("GET", ledgerReq.method)
        assertEquals("/api/v1/wallet/u1/ledger?cursor=led-0&limit=20", ledgerReq.path)
        assertEquals(BigDecimal("380.000000"), ledger.wallet.walletBalance)
        assertEquals(LedgerEntryTypeDto.ATTENTION_TIME, ledger.entries.first().type)
        assertTrue(ledger.pagination.hasMore)
        assertEquals("led-1", ledger.pagination.nextCursor)

        val created = api.createPayout(
            CreatePayoutRequest(
                userId = "u1",
                rail = PayoutRailDto.MPESA,
                destination = "+254700000003",
                amount = BigDecimal("200.00")
            )
        )
        val createReq = server.takeRequest()
        assertEquals("POST", createReq.method)
        assertEquals("/api/v1/payouts", createReq.path)
        assertTrue(createReq.body.readUtf8().contains(""""amount":"200.00""""))
        assertEquals(PayoutStatusDto.PENDING, created.payout.status)
        assertEquals("led-2", created.ledgerEntryId)
        assertEquals(BigDecimal("180.000000"), created.walletBalance)

        val list = api.getPayouts("u1")
        val listReq = server.takeRequest()
        assertEquals("GET", listReq.method)
        assertEquals("/api/v1/payouts?userId=u1", listReq.path)
        assertEquals(1, list.count)
        assertEquals(PayoutStatusDto.PAID, list.payouts.first().status)
    }
}
