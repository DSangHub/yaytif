package com.yaytif.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaytif.app.permissions.PermissionStatusChecker
import com.yaytif.app.ui.navigation.YaytifApp
import com.yaytif.app.ui.onboarding.OnboardingViewModel
import com.yaytif.app.ui.state.YaytifViewModel
import com.yaytif.app.ui.theme.YaytifTheme
import com.yaytif.app.worker.SponsoredAlertSyncWorker

class MainActivity : AppCompatActivity() {

    private val viewModel: YaytifViewModel by viewModels {
        val container = (application as YaytifApplication).appContainer
        YaytifViewModel.Factory(
            preferencesStore = container.preferencesRepository,
            sponsoredAlertRepository = container.sponsoredAlertRepository,
            localNotificationRepository = container.localNotificationRepository,
            walletRepository = container.walletRepository,
            permissionChecker = container.permissionChecker,
            enqueueImmediateSync = { countryCode, category ->
                SponsoredAlertSyncWorker.enqueueImmediateSync(
                    context = applicationContext,
                    countryCode = countryCode,
                    category = category
                )
            }
        )
    }

    private val onboardingViewModel: OnboardingViewModel by viewModels {
        val container = (application as YaytifApplication).appContainer
        OnboardingViewModel.Factory(
            apiService = container.apiService,
            preferencesStore = container.preferencesRepository,
            permissionChecker = container.permissionChecker,
            onRegisteredSync = { countryCode ->
                SponsoredAlertSyncWorker.enqueueImmediateSync(
                    context = applicationContext,
                    countryCode = countryCode,
                    category = null
                )
            }
        )
    }

    private val postNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
        onboardingViewModel.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            YaytifTheme(localeTag = preferences.localeTag) {
                YaytifApp(
                    viewModel = viewModel,
                    onboardingViewModel = onboardingViewModel,
                    onApplyAppLocale = { localeTag ->
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(localeTag)
                        )
                    },
                    onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                    onOpenOverlaySettings = ::openOverlaySettings,
                    onRequestPostNotifications = ::requestPostNotificationsPermission
                )
            }
        }
    }

    private fun openNotificationListenerSettings() {
        runCatching {
            startActivity(PermissionStatusChecker.buildNotificationListenerSettingsIntent())
        }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(PermissionStatusChecker.buildOverlayPermissionIntent(packageName))
        }
    }

    private fun requestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                startActivity(PermissionStatusChecker.buildAppNotificationSettingsIntent(packageName))
            }
        }
    }
}
