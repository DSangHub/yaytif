package com.yaytif.app

import android.app.Application
import androidx.work.Configuration
import com.yaytif.app.di.AppContainer
import com.yaytif.app.worker.LocalVaultRetentionWorker
import com.yaytif.app.worker.SponsoredAlertSyncWorker

class YaytifApplication : Application(), Configuration.Provider {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        SponsoredAlertSyncWorker.enqueuePeriodicSync(this)
        LocalVaultRetentionWorker.enqueuePeriodicRetention(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(appContainer.workerFactory)
            .build()
}
