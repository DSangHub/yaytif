package com.yaytif.app.di

import android.content.Context
import com.yaytif.app.BuildConfig
import com.yaytif.app.data.local.YaytifDatabase
import com.yaytif.app.data.preferences.UserPreferencesRepository
import com.yaytif.app.data.remote.YaytifApiFactory
import com.yaytif.app.data.remote.YaytifApiService
import com.yaytif.app.data.repository.LocalNotificationRepository
import com.yaytif.app.data.repository.SponsoredAlertRepository
import com.yaytif.app.data.repository.WalletRepository
import com.yaytif.app.ui.wallet.WalletViewModel
import com.yaytif.app.worker.YaytifWorkerFactory

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: YaytifDatabase = YaytifDatabase.getInstance(appContext)

    val preferencesRepository: UserPreferencesRepository = UserPreferencesRepository(appContext)

    val apiService: YaytifApiService = YaytifApiFactory.createService(
        baseUrl = BuildConfig.API_BASE_URL,
        okHttpClient = YaytifApiFactory.createOkHttpClient(enableBasicLogging = BuildConfig.DEBUG)
    )

    val localNotificationRepository: LocalNotificationRepository = LocalNotificationRepository(
        localNotificationDao = database.localNotificationDao()
    )

    val sponsoredAlertRepository: SponsoredAlertRepository = SponsoredAlertRepository(
        sponsoredAlertDao = database.sponsoredAlertDao(),
        apiService = apiService,
        preferencesStore = preferencesRepository
    )

    val walletRepository: WalletRepository = WalletRepository(
        apiService = apiService,
        preferencesStore = preferencesRepository
    )

    val walletViewModelFactory: WalletViewModel.Factory = WalletViewModel.Factory(
        walletRepository = walletRepository,
        preferencesStore = preferencesRepository
    )

    val workerFactory: YaytifWorkerFactory = YaytifWorkerFactory(
        sponsoredAlertRepository = sponsoredAlertRepository,
        preferencesStore = preferencesRepository
    )
}
