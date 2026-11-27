package com.yaytif.app.data.repository

import com.yaytif.app.data.local.dao.LocalNotificationDao
import com.yaytif.app.data.local.dao.VaultAppSummary
import com.yaytif.app.data.local.entity.LocalNotificationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVaultRepositoryAndRetentionTest {

    private class FakeLocalNotificationDao : LocalNotificationDao {
        private val items = MutableStateFlow<List<LocalNotificationEntity>>(emptyList())
        private var nextId = 1L

        override fun observeActiveNotifications(): Flow<List<LocalNotificationEntity>> {
            return items.map { list ->
                list.filter { !it.isArchived }.sortedByDescending { it.receivedAtEpochMs }
            }
        }

        override fun observeFilteredNotifications(
            query: String,
            unreadOnly: Boolean,
            packageName: String?
        ): Flow<List<LocalNotificationEntity>> {
            val q = query.lowercase()
            return items.map { list ->
                list.filter { entry ->
                    !entry.isArchived &&
                        (!unreadOnly || !entry.isRead) &&
                        (packageName == null || entry.packageName == packageName) &&
                        (
                            q.isEmpty() ||
                                entry.title.lowercase().contains(q) ||
                                entry.body.lowercase().contains(q) ||
                                entry.appLabel.lowercase().contains(q) ||
                                (entry.senderName?.lowercase()?.contains(q) == true)
                            )
                }.sortedByDescending { it.receivedAtEpochMs }
            }
        }

        override fun observeDistinctApps(): Flow<List<VaultAppSummary>> {
            return items.map { list ->
                list.filter { !it.isArchived }
                    .map { VaultAppSummary(it.packageName, it.appLabel) }
                    .distinct()
                    .sortedBy { it.appLabel }
            }
        }

        override fun observeUnreadCount(): Flow<Int> {
            return items.map { list -> list.count { !it.isArchived && !it.isRead } }
        }

        override suspend fun getById(id: Long): LocalNotificationEntity? {
            return items.value.firstOrNull { it.id == id }
        }

        override suspend fun upsert(notification: LocalNotificationEntity): Long {
            val current = items.value.toMutableList()
            val existingIdx = current.indexOfFirst { it.notificationKey == notification.notificationKey }
            val saved = if (existingIdx >= 0) {
                val id = current[existingIdx].id
                notification.copy(id = id).also { current[existingIdx] = it }
            } else {
                val id = if (notification.id > 0L) notification.id else nextId++
                notification.copy(id = id).also { current.add(it) }
            }
            items.value = current
            return saved.id
        }

        override suspend fun markAsRead(id: Long) {
            items.value = items.value.map { if (it.id == id) it.copy(isRead = true) else it }
        }

        override suspend fun markAsUnread(id: Long) {
            items.value = items.value.map { if (it.id == id) it.copy(isRead = false) else it }
        }

        override suspend fun markAllAsRead() {
            items.value = items.value.map { if (!it.isArchived) it.copy(isRead = true) else it }
        }

        override suspend fun archive(id: Long) {
            items.value = items.value.map {
                if (it.id == id) it.copy(isArchived = true, isRead = true) else it
            }
        }

        override suspend fun deleteById(id: Long) {
            items.value = items.value.filterNot { it.id == id }
        }

        override suspend fun deleteOlderThan(cutoffEpochMs: Long): Int {
            val before = items.value.size
            items.value = items.value.filter { it.receivedAtEpochMs >= cutoffEpochMs }
            return before - items.value.size
        }

        override suspend fun clearAll() {
            items.value = emptyList()
        }
    }

    @Test
    fun `supports local search, unread filter, package filter, read-unread toggle, delete and clearAll`() = runTest {
        val dao = FakeLocalNotificationDao()
        val repository = LocalNotificationRepository(dao)

        val whatsappId = repository.saveInterceptedNotification(
            LocalNotificationEntity(
                notificationKey = "wa|1",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                senderName = "Chidi",
                title = "Family Chat",
                body = "Dinner at 8pm",
                category = "msg",
                priorityColor = "#10B981",
                receivedAtEpochMs = 3_000L
            )
        )
        val bankId = repository.saveInterceptedNotification(
            LocalNotificationEntity(
                notificationKey = "bank|1",
                packageName = "com.nubank.app",
                appLabel = "Nubank",
                senderName = null,
                title = "Pix recebido",
                body = "R$ 150,00 de Ana",
                category = "email",
                priorityColor = "#3B82F6",
                receivedAtEpochMs = 4_000L
            )
        )

        // Search by sender, body, title, and app label (trimmed)
        assertEquals(1, repository.observeFilteredNotifications(query = "  chidi ").first().size)
        assertEquals(1, repository.observeFilteredNotifications(query = "Pix").first().size)
        assertEquals(1, repository.observeFilteredNotifications(query = "Nubank").first().size)

        // Distinct apps
        assertEquals(listOf("Nubank", "WhatsApp"), repository.observeDistinctApps().first().map { it.appLabel })

        // Package filter
        val onlyWhatsApp = repository.observeFilteredNotifications(packageName = "com.whatsapp").first()
        assertEquals(listOf(whatsappId), onlyWhatsApp.map { it.id })

        // Mark read & unread filter
        repository.markAsRead(bankId)
        assertEquals(1, repository.observeUnreadCount().first())
        assertEquals(
            listOf(whatsappId),
            repository.observeFilteredNotifications(unreadOnly = true).first().map { it.id }
        )

        // Mark unread again
        repository.markAsUnread(bankId)
        assertEquals(2, repository.observeUnreadCount().first())
        assertFalse(dao.getById(bankId)!!.isRead)

        // Delete single entry
        repository.deleteById(whatsappId)
        assertEquals(listOf(bankId), repository.observeActiveNotifications().first().map { it.id })

        // Clear all
        repository.clearAll()
        assertTrue(repository.observeActiveNotifications().first().isEmpty())
    }

    @Test
    fun `pruneOlderThanDays removes only notifications strictly older than retention window`() = runTest {
        val dao = FakeLocalNotificationDao()
        val repository = LocalNotificationRepository(dao)

        val now = 1_750_000_000_000L
        val thirtyOneDaysAgo = now - 31L * LocalNotificationRepository.MILLIS_PER_DAY
        val exactCutoff = now - 30L * LocalNotificationRepository.MILLIS_PER_DAY
        val twoDaysAgo = now - 2L * LocalNotificationRepository.MILLIS_PER_DAY

        repository.saveInterceptedNotification(
            LocalNotificationEntity(
                notificationKey = "old|1",
                packageName = "com.example.old",
                appLabel = "OldApp",
                senderName = null,
                title = "Expired",
                body = "Should be pruned",
                category = null,
                priorityColor = "#10B981",
                receivedAtEpochMs = thirtyOneDaysAgo
            )
        )
        repository.saveInterceptedNotification(
            LocalNotificationEntity(
                notificationKey = "boundary|1",
                packageName = "com.example.boundary",
                appLabel = "BoundaryApp",
                senderName = null,
                title = "Exact cutoff",
                body = "Should remain",
                category = null,
                priorityColor = "#10B981",
                receivedAtEpochMs = exactCutoff
            )
        )
        repository.saveInterceptedNotification(
            LocalNotificationEntity(
                notificationKey = "recent|1",
                packageName = "com.example.recent",
                appLabel = "RecentApp",
                senderName = null,
                title = "Recent",
                body = "Should remain",
                category = null,
                priorityColor = "#10B981",
                receivedAtEpochMs = twoDaysAgo
            )
        )

        val prunedCount = repository.pruneOlderThanDays(
            retentionDays = LocalNotificationRepository.DEFAULT_RETENTION_DAYS,
            nowEpochMs = now
        )

        assertEquals(1, prunedCount)
        val remainingKeys = repository.observeActiveNotifications().first().map { it.notificationKey }
        assertEquals(listOf("recent|1", "boundary|1"), remainingKeys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pruneOlderThanDays rejects non-positive retention days`() = runTest {
        val repository = LocalNotificationRepository(FakeLocalNotificationDao())
        repository.pruneOlderThanDays(retentionDays = 0, nowEpochMs = 1_750_000_000_000L)
    }
}
