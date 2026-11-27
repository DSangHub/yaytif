package com.yaytif.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Strictly on-device storage for intercepted personal notifications.
 *
 * PRIVACY INVARIANT (.cursorrules):
 * Rows in this table MUST NEVER be serialized into network DTOs, logged with
 * third-party notification content, or transmitted to any Yaytif backend endpoint.
 */
@Entity(
    tableName = "local_notifications",
    indices = [
        Index(value = ["notification_key"], unique = true),
        Index(value = ["is_archived", "received_at_epoch_ms"]),
        Index(value = ["package_name"])
    ]
)
data class LocalNotificationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "notification_key")
    val notificationKey: String,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "app_label")
    val appLabel: String,

    @ColumnInfo(name = "sender_name")
    val senderName: String? = null,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "priority_color")
    val priorityColor: String = "#10B981",

    @ColumnInfo(name = "received_at_epoch_ms")
    val receivedAtEpochMs: Long,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false
)
