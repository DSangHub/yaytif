package com.yaytif.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationExtractorTest {

    @Test
    fun `filters out Yaytif own package`() {
        val result = NotificationExtractor.extract(
            RawNotificationPayload(
                packageName = NotificationExtractor.YAYTIF_PACKAGE_NAME,
                appLabel = "Yaytif",
                title = "Sponsored Alert",
                text = "Earn rewards now",
                postTimeEpochMs = 1_700_000_000_000L
            )
        )
        assertNull(result)
    }

    @Test
    fun `filters out blank package name`() {
        val result = NotificationExtractor.extract(
            RawNotificationPayload(
                packageName = "   ",
                title = "Hello",
                text = "World",
                postTimeEpochMs = 1_700_000_000_000L
            )
        )
        assertNull(result)
    }

    @Test
    fun `filters out ongoing, foreground service, and group summary notifications`() {
        val base = RawNotificationPayload(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Maria",
            text = "See you at 5pm",
            postTimeEpochMs = 1_700_000_000_000L
        )

        assertNull(NotificationExtractor.extract(base.copy(isOngoing = true)))
        assertNull(NotificationExtractor.extract(base.copy(isForegroundService = true)))
        assertNull(NotificationExtractor.extract(base.copy(isGroupSummary = true)))
    }

    @Test
    fun `filters out notifications with blank title and blank body`() {
        val result = NotificationExtractor.extract(
            RawNotificationPayload(
                packageName = "com.example.chat",
                appLabel = "Chat",
                title = "   ",
                text = "",
                bigText = "\n  ",
                conversationTitle = null,
                subText = " ",
                postTimeEpochMs = 1_700_000_000_000L
            )
        )
        assertNull(result)
    }

    @Test
    fun `prefers bigText over text and falls back to text when bigText is blank`() {
        val withBigText = NotificationExtractor.extract(
            RawNotificationPayload(
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                statusBarKey = "0|com.whatsapp|101|null|1001",
                title = "Family Group",
                text = "Short preview",
                bigText = "Full expanded notification message body",
                senderName = "Tunde",
                category = "msg",
                postTimeEpochMs = 1_700_000_000_000L
            )
        )

        assertNotNull(withBigText)
        assertEquals("Full expanded notification message body", withBigText!!.body)
        assertEquals("Family Group", withBigText.title)
        assertEquals("Tunde", withBigText.senderName)
        assertEquals("WhatsApp", withBigText.appLabel)
        assertEquals("msg", withBigText.category)
        assertEquals(1_700_000_000_000L, withBigText.receivedAtEpochMs)
        assertFalse(withBigText.isRead)
        assertFalse(withBigText.isArchived)

        val fallbackToText = NotificationExtractor.extract(
            RawNotificationPayload(
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                title = "Amina",
                text = "Standard short text",
                bigText = "   ",
                postTimeEpochMs = 1_700_000_000_500L
            )
        )

        assertNotNull(fallbackToText)
        assertEquals("Standard short text", fallbackToText!!.body)
    }

    @Test
    fun `omits duplicate sender when sender matches resolved title`() {
        val result = NotificationExtractor.extract(
            RawNotificationPayload(
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                title = "Carlos Silva",
                senderName = "carlos silva",
                text = "Oi! Tudo bem?",
                postTimeEpochMs = 1_700_000_001_000L
            )
        )

        assertNotNull(result)
        assertEquals("Carlos Silva", result!!.title)
        assertNull(result.senderName)
    }

    @Test
    fun `generates deterministic deduplication key and distinguishes different post times`() {
        val payload = RawNotificationPayload(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            statusBarKey = "0|com.whatsapp|42|chat|10234",
            title = "Ngozi",
            text = "Arrived safely",
            postTimeEpochMs = 1_700_000_010_000L
        )

        val first = NotificationExtractor.extract(payload)
        val duplicate = NotificationExtractor.extract(payload)
        val laterUpdate = NotificationExtractor.extract(payload.copy(postTimeEpochMs = 1_700_000_020_000L))

        assertNotNull(first)
        assertNotNull(duplicate)
        assertNotNull(laterUpdate)
        assertEquals(first!!.notificationKey, duplicate!!.notificationKey)
        assertNotEquals(first.notificationKey, laterUpdate!!.notificationKey)
    }

    @Test
    fun `falls back to packageName when appLabel is missing and truncates overly long fields`() {
        val longTitle = "T".repeat(NotificationExtractor.MAX_TITLE_LENGTH + 50)
        val longBody = "B".repeat(NotificationExtractor.MAX_BODY_LENGTH + 500)
        val longSender = "S".repeat(NotificationExtractor.MAX_SENDER_LENGTH + 40)

        val result = NotificationExtractor.extract(
            RawNotificationPayload(
                packageName = "com.bank.alerts",
                appLabel = null,
                title = longTitle,
                bigText = longBody,
                senderName = longSender,
                postTimeEpochMs = 1_700_000_030_000L
            )
        )

        assertNotNull(result)
        assertEquals("com.bank.alerts", result!!.appLabel)
        assertEquals(NotificationExtractor.MAX_TITLE_LENGTH, result.title.length)
        assertEquals(NotificationExtractor.MAX_BODY_LENGTH, result.body.length)
        assertEquals(NotificationExtractor.MAX_SENDER_LENGTH, result.senderName?.length)
    }
}
