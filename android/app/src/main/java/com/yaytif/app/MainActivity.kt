package com.yaytif.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaytif.app.ui.navigation.YaytifApp
import com.yaytif.app.ui.state.YaytifViewModel
import com.yaytif.app.ui.theme.YaytifTheme
import com.yaytif.app.worker.SponsoredAlertSyncWorker

class MainActivity : AppCompatActivity() {

    private val viewModel: YaytifViewModel by viewModels {
        val container = (application as YaytifApplication).appContainer
        YaytifViewModel.Factory(
            preferencesStore = container.preferencesRepository,
            sponsoredAlertRepository = container.sponsoredAlertRepository,
            adSessionRepository = container.adSessionRepository,
            localNotificationRepository = container.localNotificationRepository,
            walletRepository = container.walletRepository,
            enqueueImmediateSync = { countryCode, category ->
                SponsoredAlertSyncWorker.enqueueImmediateSync(
                    context = applicationContext,
                    countryCode = countryCode,
                    category = category
                )
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            YaytifTheme(localeTag = preferences.localeTag) {
                YaytifApp(
                    viewModel = viewModel,
                    onApplyAppLocale = { localeTag ->
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(localeTag)
                        )
                    }
                )
            }
        }
    }
}
