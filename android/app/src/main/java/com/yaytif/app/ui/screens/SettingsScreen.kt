package com.yaytif.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yaytif.app.R
import com.yaytif.app.data.preferences.UserPreferences
import com.yaytif.app.domain.MarketConfig
import com.yaytif.app.permissions.PermissionStatus
import com.yaytif.app.ui.onboarding.PermissionStatusCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preferences: UserPreferences,
    permissionStatus: PermissionStatus,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onSelectLocale: (String) -> Unit,
    onSelectMarket: (String) -> Unit,
    onToggleInterest: (String) -> Unit,
    onClearLocalVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    var vaultClearedBanner by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.settings_language_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_language_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MarketConfig.SUPPORTED_LOCALES.forEach { locale ->
                            FilterChip(
                                selected = preferences.localeTag.equals(locale.languageTag, ignoreCase = true),
                                onClick = { onSelectLocale(locale.languageTag) },
                                label = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(text = locale.nativeDisplayName)
                                        if (locale.isRtl) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.settings_rtl_badge),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.settings_market_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MarketConfig.SUPPORTED_MARKETS.forEach { market ->
                            FilterChip(
                                selected = preferences.countryCode.equals(market.countryCode, ignoreCase = true),
                                onClick = { onSelectMarket(market.countryCode) },
                                label = { Text(text = "${market.displayName} (${market.currency})") }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.settings_interests_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MarketConfig.AVAILABLE_CATEGORIES.forEach { category ->
                            FilterChip(
                                selected = preferences.interests.contains(category),
                                onClick = { onToggleInterest(category) },
                                label = { Text(text = stringResource(categoryLabelRes(category))) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = stringResource(R.string.permissions_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                PermissionStatusCard(
                    title = stringResource(R.string.permission_listener_title),
                    description = stringResource(R.string.permission_listener_desc),
                    isGranted = permissionStatus.notificationListenerGranted,
                    actionLabel = stringResource(R.string.permission_open_settings),
                    onAction = onOpenNotificationListenerSettings
                )
                PermissionStatusCard(
                    title = stringResource(R.string.permission_overlay_title),
                    description = stringResource(R.string.permission_overlay_desc),
                    isGranted = permissionStatus.overlayGranted,
                    actionLabel = stringResource(R.string.permission_open_settings),
                    onAction = onOpenOverlaySettings
                )
                if (permissionStatus.postNotificationsRequired) {
                    PermissionStatusCard(
                        title = stringResource(R.string.permission_post_notifications_title),
                        description = stringResource(R.string.permission_post_notifications_desc),
                        isGranted = permissionStatus.postNotificationsGranted,
                        actionLabel = stringResource(R.string.permission_grant_action),
                        onAction = onRequestPostNotifications
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.settings_privacy_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            onClearLocalVault()
                            vaultClearedBanner = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.settings_clear_vault))
                    }
                    if (vaultClearedBanner) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_vault_cleared),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
