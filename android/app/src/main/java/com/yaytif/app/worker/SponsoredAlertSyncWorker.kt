package com.yaytif.app.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yaytif.app.data.preferences.UserPreferencesStore
import com.yaytif.app.data.repository.SponsoredAlertRepository
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that fetches active SponsoredAlert campaigns from /api/v1/alerts
 * and caches them locally in Room.
 */
class SponsoredAlertSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val sponsoredAlertRepository: SponsoredAlertRepository,
    private val preferencesStore: UserPreferencesStore
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = preferencesStore.getSnapshot()
        val countryCode = inputData.getString(KEY_COUNTRY_CODE) ?: prefs.countryCode
        val category = inputData.getString(KEY_CATEGORY) ?: prefs.selectedCategory

        return try {
            val syncedCount = sponsoredAlertRepository.syncSponsoredAlerts(
                userId = prefs.userId,
                countryCode = countryCode,
                category = category
            )
            Result.success(workDataOf(KEY_SYNCED_COUNT to syncedCount))
        } catch (httpEx: HttpException) {
            val code = httpEx.code()
            val isTransient = code == 429 || code >= 500
            if (isTransient && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR_CODE to code))
            }
        } catch (_: IOException) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "yaytif_sponsored_alert_periodic_sync"
        const val IMMEDIATE_WORK_NAME = "yaytif_sponsored_alert_immediate_sync"

        const val KEY_COUNTRY_CODE = "country_code"
        const val KEY_CATEGORY = "category"
        const val KEY_SYNCED_COUNT = "synced_count"
        const val KEY_ERROR_CODE = "error_code"

        private const val PERIODIC_INTERVAL_MINUTES = 30L
        private const val BACKOFF_DELAY_SECONDS = 30L
        private const val MAX_RETRY_ATTEMPTS = 3

        private fun connectedConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueuePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<SponsoredAlertSyncWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(connectedConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_SECONDS,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueImmediateSync(
            context: Context,
            countryCode: String? = null,
            category: String? = null
        ) {
            val input = Data.Builder().apply {
                if (!countryCode.isNullOrBlank()) putString(KEY_COUNTRY_CODE, countryCode)
                if (!category.isNullOrBlank()) putString(KEY_CATEGORY, category)
            }.build()

            val request = OneTimeWorkRequestBuilder<SponsoredAlertSyncWorker>()
                .setConstraints(connectedConstraints())
                .setInputData(input)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_SECONDS,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

class YaytifWorkerFactory(
    private val sponsoredAlertRepository: SponsoredAlertRepository,
    private val preferencesStore: UserPreferencesStore
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            SponsoredAlertSyncWorker::class.java.name -> SponsoredAlertSyncWorker(
                appContext = appContext,
                workerParams = workerParameters,
                sponsoredAlertRepository = sponsoredAlertRepository,
                preferencesStore = preferencesStore
            )
            else -> null
        }
    }
}
