package com.yaytif.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yaytif.app.ui.onboarding.OnboardingScreen
import com.yaytif.app.ui.onboarding.OnboardingViewModel
import com.yaytif.app.ui.screens.FeedScreen
import com.yaytif.app.ui.screens.SettingsScreen
import com.yaytif.app.ui.screens.VaultScreen
import com.yaytif.app.ui.screens.WalletScreen
import com.yaytif.app.ui.state.YaytifViewModel

@Composable
fun YaytifApp(
    viewModel: YaytifViewModel,
    onboardingViewModel: OnboardingViewModel,
    onApplyAppLocale: (String) -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPreferencesLoaded by viewModel.isPreferencesLoaded.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    if (!isPreferencesLoaded) {
        Surface(modifier = modifier.fillMaxSize()) {}
        return
    }

    if (!preferences.onboardingCompleted) {
        OnboardingScreen(
            viewModel = onboardingViewModel,
            onApplyAppLocale = onApplyAppLocale,
            onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
            onOpenOverlaySettings = onOpenOverlaySettings,
            onRequestPostNotifications = onRequestPostNotifications,
            modifier = modifier
        )
        return
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val alerts by viewModel.sponsoredAlerts.collectAsStateWithLifecycle()
    val localNotifications by viewModel.localNotifications.collectAsStateWithLifecycle()
    val unreadVaultCount by viewModel.unreadVaultCount.collectAsStateWithLifecycle()
    val isSyncingAlerts by viewModel.isSyncingAlerts.collectAsStateWithLifecycle()
    val walletState by viewModel.walletState.collectAsStateWithLifecycle()
    val permissionStatus by viewModel.permissionStatus.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                YaytifDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (destination == YaytifDestination.Vault && unreadVaultCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge {
                                            Text(text = unreadVaultCount.coerceAtMost(99).toString())
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = stringResource(destination.labelRes)
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = stringResource(destination.labelRes)
                                )
                            }
                        },
                        label = { Text(text = stringResource(destination.labelRes)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = YaytifDestination.Feed.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(YaytifDestination.Feed.route) {
                FeedScreen(
                    alerts = alerts,
                    selectedCategory = preferences.selectedCategory,
                    currencyCode = preferences.currency,
                    isSyncing = isSyncingAlerts,
                    onSelectCategory = viewModel::selectCategory,
                    onSyncNow = viewModel::triggerAlertSync
                )
            }
            composable(YaytifDestination.Vault.route) {
                VaultScreen(
                    notifications = localNotifications,
                    unreadCount = unreadVaultCount,
                    onMarkRead = viewModel::markNotificationRead,
                    onMarkAllRead = viewModel::markAllNotificationsRead,
                    onArchive = viewModel::archiveNotification
                )
            }
            composable(YaytifDestination.Wallet.route) {
                WalletScreen(
                    walletState = walletState,
                    countryCode = preferences.countryCode,
                    currencyCode = preferences.currency,
                    onSubmitPayout = viewModel::submitPayout
                )
            }
            composable(YaytifDestination.Settings.route) {
                SettingsScreen(
                    preferences = preferences,
                    permissionStatus = permissionStatus,
                    onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onRequestPostNotifications = onRequestPostNotifications,
                    onSelectLocale = { localeTag ->
                        viewModel.updateLocale(localeTag)
                        onApplyAppLocale(localeTag)
                    },
                    onSelectMarket = viewModel::updateMarket,
                    onToggleInterest = viewModel::toggleInterest,
                    onClearLocalVault = viewModel::clearLocalVault
                )
            }
        }
    }
}
