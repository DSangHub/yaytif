package com.yaytif.app.ui.onboarding

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaytif.app.R
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.domain.MarketConfig
import com.yaytif.app.permissions.PermissionStatus
import com.yaytif.app.ui.screens.categoryLabelRes

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onApplyAppLocale: (String) -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    val totalSteps = OnboardingStep.entries.size
    val currentStepNumber = state.currentStep.ordinal + 1

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(
                                R.string.onboarding_step_progress,
                                currentStepNumber,
                                totalSteps
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { currentStepNumber.toFloat() / totalSteps.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            when (state.currentStep) {
                OnboardingStep.MARKET -> item {
                    MarketAndLanguageStep(
                        selectedLocaleTag = state.selectedLocaleTag,
                        selectedCountryCode = state.selectedCountryCode,
                        onSelectLocale = { localeTag ->
                            viewModel.selectLocale(localeTag)
                            onApplyAppLocale(localeTag)
                        },
                        onSelectMarket = viewModel::selectMarket,
                        onContinue = viewModel::nextFromMarket
                    )
                }

                OnboardingStep.INTERESTS -> item {
                    InterestsStep(
                        selectedInterests = state.selectedInterests,
                        error = state.error,
                        onToggleInterest = viewModel::toggleInterest,
                        onBack = viewModel::goBack,
                        onContinue = viewModel::nextFromInterests
                    )
                }

                OnboardingStep.PHONE_REGISTRATION -> item {
                    PhoneRegistrationStep(
                        countryCode = state.selectedCountryCode,
                        phoneInput = state.phoneInput,
                        isRegistering = state.isRegistering,
                        error = state.error,
                        onPhoneChange = viewModel::updatePhoneInput,
                        onBack = viewModel::goBack,
                        onRegister = viewModel::registerAndContinue
                    )
                }

                OnboardingStep.PERMISSIONS -> item {
                    PermissionsStep(
                        permissionStatus = state.permissionStatus,
                        onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                        onRequestPostNotifications = onRequestPostNotifications,
                        onBack = viewModel::goBack,
                        onFinish = viewModel::completeOnboarding
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketAndLanguageStep(
    selectedLocaleTag: String,
    selectedCountryCode: String,
    onSelectLocale: (String) -> Unit,
    onSelectMarket: (String) -> Unit,
    onContinue: () -> Unit
) {
    val activeMarket = MarketConfig.marketForCountry(selectedCountryCode)
    val railsSummary = activeMarket.supportedRails
        .map { railDisplayName(it) }
        .joinToString(separator = " • ")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.onboarding_market_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.onboarding_market_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
                    text = stringResource(R.string.onboarding_language_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarketConfig.SUPPORTED_LOCALES.forEach { locale ->
                        FilterChip(
                            selected = selectedLocaleTag.equals(locale.languageTag, ignoreCase = true),
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
                    text = stringResource(R.string.onboarding_market_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarketConfig.SUPPORTED_MARKETS.forEach { market ->
                        FilterChip(
                            selected = selectedCountryCode.equals(market.countryCode, ignoreCase = true),
                            onClick = { onSelectMarket(market.countryCode) },
                            label = { Text(text = "${market.displayName} (${market.currency})") }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.onboarding_payout_rails_preview, railsSummary),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.onboarding_continue))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterestsStep(
    selectedInterests: Set<String>,
    error: OnboardingError?,
    onToggleInterest: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.onboarding_interests_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.onboarding_interests_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MarketConfig.AVAILABLE_CATEGORIES.forEach { category ->
                    FilterChip(
                        selected = selectedInterests.contains(category),
                        onClick = { onToggleInterest(category) },
                        label = { Text(text = stringResource(categoryLabelRes(category))) }
                    )
                }
            }
        }

        if (error == OnboardingError.SelectAtLeastOneInterest) {
            Text(
                text = stringResource(R.string.onboarding_error_select_interest),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.onboarding_back))
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.onboarding_continue))
            }
        }
    }
}

@Composable
private fun PhoneRegistrationStep(
    countryCode: String,
    phoneInput: String,
    isRegistering: Boolean,
    error: OnboardingError?,
    onPhoneChange: (String) -> Unit,
    onBack: () -> Unit,
    onRegister: () -> Unit
) {
    val market = MarketConfig.marketForCountry(countryCode)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.onboarding_phone_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.onboarding_phone_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_phone_privacy_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        OutlinedTextField(
            value = phoneInput,
            onValueChange = onPhoneChange,
            label = { Text(text = stringResource(R.string.onboarding_phone_label, market.callingCode)) },
            placeholder = { Text(text = stringResource(R.string.onboarding_phone_hint)) },
            prefix = { Text(text = "${market.callingCode} ") },
            singleLine = true,
            enabled = !isRegistering,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        if (error != null) {
            val errorText = when (error) {
                OnboardingError.PhoneEmpty -> stringResource(R.string.onboarding_error_phone_empty)
                OnboardingError.PhoneInvalid -> stringResource(R.string.onboarding_error_phone_invalid)
                OnboardingError.PhoneTooShort -> stringResource(R.string.onboarding_error_phone_short)
                OnboardingError.PhoneTooLong -> stringResource(R.string.onboarding_error_phone_long)
                OnboardingError.SelectAtLeastOneInterest -> stringResource(R.string.onboarding_error_select_interest)
                is OnboardingError.RegistrationFailed -> {
                    if (error.statusCode != null) {
                        stringResource(R.string.onboarding_error_registration_http, error.statusCode)
                    } else {
                        stringResource(R.string.onboarding_error_registration_failed)
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    if (error is OnboardingError.RegistrationFailed) {
                        TextButton(onClick = onRegister, enabled = !isRegistering) {
                            Text(text = stringResource(R.string.onboarding_retry))
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                enabled = !isRegistering,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.onboarding_back))
            }
            Button(
                onClick = onRegister,
                enabled = !isRegistering,
                modifier = Modifier.weight(1f)
            ) {
                if (isRegistering) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(text = stringResource(R.string.onboarding_registering))
                } else {
                    Text(text = stringResource(R.string.onboarding_register_cta))
                }
            }
        }
    }
}

@Composable
private fun PermissionsStep(
    permissionStatus: PermissionStatus,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.onboarding_permissions_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = stringResource(R.string.vault_privacy_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.vault_privacy_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.onboarding_back))
            }
            Button(
                onClick = onFinish,
                modifier = Modifier.weight(1f)
            ) {
                val labelRes = if (permissionStatus.corePermissionsGranted) {
                    R.string.onboarding_finish
                } else {
                    R.string.onboarding_skip_for_now
                }
                Text(text = stringResource(labelRes))
            }
        }
    }
}

@Composable
fun PermissionStatusCard(
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isGranted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isGranted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = if (isGranted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(
                                if (isGranted) R.string.permissions_status_granted else R.string.permissions_status_needed
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isGranted) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isGranted) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
private fun railDisplayName(rail: PayoutRailDto): String = when (rail) {
    PayoutRailDto.PIX -> stringResource(R.string.rail_pix)
    PayoutRailDto.MERCADO_PAGO -> stringResource(R.string.rail_mercado_pago)
    PayoutRailDto.MPESA -> stringResource(R.string.rail_mpesa)
    PayoutRailDto.PAYSTACK_OPAY -> stringResource(R.string.rail_paystack_opay)
    PayoutRailDto.AIRTIME -> stringResource(R.string.rail_airtime)
    PayoutRailDto.PAYPAL -> stringResource(R.string.rail_paypal)
}
