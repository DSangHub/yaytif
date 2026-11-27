package com.yaytif.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yaytif.app.data.repository.LocalNotificationRepository
import java.util.concurrent.TimeUnit

/**
 * Strictly on-device WorkManager worker that prunes aged notifications from Room.
 * Requires no network access and never transmits notification data off-device.
 */
class LocalVaultRetentionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val localNotificationRepository: LocalNotificationRepository,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val retentionDays = inputData.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
            .coerceAtLeast(1)

        val prunedCount = localNotificationRepository.pruneOlderThanDays(
            retentionDays = retentionDays,
            nowEpochMs = clockMillis()
        )
        return Result.success(workDataOf(KEY_PRUNED_COUNT to prunedCount))
    }

    companion object {
        const val PERIODIC_WORK_NAME = "yaytif_local_vault_retention_periodic"
        const val IMMEDIATE_WORK_NAME = "yaytif_local_vault_retention_immediate"

        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_PRUNED_COUNT = "pruned_count"
        const val DEFAULT_RETENTION_DAYS = LocalNotificationRepository.DEFAULT_RETENTION_DAYS

        private const val RETENTION_INTERVAL_HOURS = 24L

        fun enqueuePeriodicRetention(
            context: Context,
            retentionDays: Int = DEFAULT_RETENTION_DAYS
        ) {
            val input = Data.Builder()
                .putInt(KEY_RETENTION_DAYS, retentionDays.coerceAtLeast(1))
                .build()

            val request = PeriodicWorkRequestBuilder<LocalVaultRetentionWorker>(
                RETENTION_INTERVAL_HOURS,
                TimeUnit.HOURS
            )
                .setInputData(input)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueImmediateRetention(
            context: Context,
            retentionDays: Int = DEFAULT_RETENTION_DAYS
        ) {
            val input = Data.Builder()
                .putInt(KEY_RETENTION_DAYS, retentionDays.coerceAtLeast(1))
                .build()

            val request = OneTimeWorkRequestBuilder<LocalVaultRetentionWorker>()
                .setInputData(input)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
