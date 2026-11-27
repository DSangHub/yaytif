package com.yaytif.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaytif.app.R
import com.yaytif.app.data.remote.dto.LedgerEntryDto
import com.yaytif.app.data.remote.dto.LedgerEntryTypeDto
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.data.remote.dto.PayoutRequestDto
import com.yaytif.app.data.remote.dto.PayoutStatusDto
import com.yaytif.app.domain.DestinationErrorReason
import com.yaytif.app.domain.MoneyFormatter
import com.yaytif.app.domain.PayoutRailValidator
import com.yaytif.app.ui.theme.YaytifEmerald
import com.yaytif.app.ui.theme.YaytifEmeraldDark
import com.yaytif.app.ui.wallet.PendingPayoutConfirmation
import com.yaytif.app.ui.wallet.WalletAmountError
import com.yaytif.app.ui.wallet.WalletFeedback
import com.yaytif.app.ui.wallet.WalletTab
import com.yaytif.app.ui.wallet.WalletUiState
import java.math.BigDecimal

private val LedgerCashbackBadgeColor = Color(0xFF0EA5E9)
private val LedgerWithdrawalBadgeColor = Color(0xFFF59E0B)
private val LedgerRefundBadgeColor = Color(0xFF8B5CF6)
private val PayoutPendingBadgeColor = Color(0xFFF59E0B)
private val PayoutProcessingBadgeColor = Color(0xFF0EA5E9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    walletState: WalletUiState,
    onRefresh: () -> Unit,
    onSelectTab: (WalletTab) -> Unit,
    onSelectRail: (PayoutRailDto) -> Unit,
    onDestinationChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onApplyQuickAmount: (Int) -> Unit,
    onRequestConfirmation: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onConfirmSubmitPayout: () -> Unit,
    onLoadMoreLedger: () -> Unit,
    modifier: Modifier = Modifier
) {
    walletState.pendingConfirmation?.let { confirmation ->
        PayoutConfirmationDialog(
            confirmation = confirmation,
            onConfirm = onConfirmSubmitPayout,
            onDismiss = onDismissConfirmation
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.wallet_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )

                IconButton(
                    onClick = onRefresh,
                    enabled = !walletState.isRefreshing
                ) {
                    if (walletState.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.wallet_refresh_content_desc)
                        )
                    }
                }
            }
        }

        item {
            WalletHeroCard(walletState = walletState)
        }

        walletState.feedback?.let { feedback ->
            item {
                WalletFeedbackBanner(feedback = feedback)
            }
        }

        item {
            val selectedIndex = WalletTab.entries.indexOf(walletState.selectedTab)
            PrimaryTabRow(selectedTabIndex = selectedIndex) {
                WalletTab.entries.forEach { tab ->
                    Tab(
                        selected = walletState.selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = {
                            Text(
                                text = stringResource(tabLabelRes(tab)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }

        when (walletState.selectedTab) {
            WalletTab.WITHDRAW -> {
                item {
                    WithdrawSectionCard(
                        walletState = walletState,
                        onSelectRail = onSelectRail,
                        onDestinationChange = onDestinationChange,
                        onAmountChange = onAmountChange,
                        onApplyQuickAmount = onApplyQuickAmount,
                        onRequestConfirmation = onRequestConfirmation
                    )
                }
            }

            WalletTab.LEDGER -> {
                item {
                    Text(
                        text = stringResource(R.string.wallet_ledger_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (walletState.ledgerEntries.isEmpty()) {
                    item {
                        EmptyWalletCard(message = stringResource(R.string.wallet_ledger_empty))
                    }
                } else {
                    items(walletState.ledgerEntries, key = { it.id }) { entry ->
                        LedgerEntryCard(entry = entry)
                    }

                    if (walletState.hasMoreLedger) {
                        item {
                            OutlinedButton(
                                onClick = onLoadMoreLedger,
                                enabled = !walletState.isLoadingMoreLedger,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (walletState.isLoadingMoreLedger) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(text = stringResource(R.string.wallet_loading_more))
                                } else {
                                    Text(text = stringResource(R.string.wallet_load_more))
                                }
                            }
                        }
                    }
                }
            }

            WalletTab.PAYOUTS -> {
                if (walletState.payouts.isEmpty()) {
                    item {
                        EmptyWalletCard(message = stringResource(R.string.wallet_payouts_empty))
                    }
                } else {
                    items(walletState.payouts, key = { it.id }) { payout ->
                        PayoutRequestCard(payout = payout)
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletHeroCard(walletState: WalletUiState) {
    val currency = walletState.currency
    val cap = walletState.dailyCapProgress
    val progressFloat = cap.fraction.toFloat().coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.wallet_balance_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = MoneyFormatter.formatAmount(walletState.walletBalance, currency),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(
                    R.string.wallet_daily_earned_label,
                    MoneyFormatter.formatAmount(walletState.dailyTimeEarned, currency)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progressFloat },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val capText = if (cap.isCapReached) {
                    stringResource(R.string.wallet_daily_cap_reached)
                } else {
                    stringResource(
                        R.string.wallet_daily_cap_label,
                        MoneyFormatter.formatAmount(cap.earned, currency),
                        MoneyFormatter.formatAmount(cap.capLimit, currency)
                    )
                }
                Text(
                    text = capText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )

                walletState.resetCountdown?.let { countdown ->
                    val resetLabel = if (countdown.isResettingSoon) {
                        stringResource(R.string.wallet_reset_soon)
                    } else {
                        stringResource(
                            R.string.wallet_reset_countdown,
                            countdown.hours,
                            countdown.minutes
                        )
                    }
                    Text(
                        text = resetLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletFeedbackBanner(feedback: WalletFeedback) {
    val isSuccess = feedback == WalletFeedback.PayoutSubmitted
    val containerColor = if (isSuccess) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (isSuccess) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val message = when (feedback) {
        WalletFeedback.PayoutSubmitted -> stringResource(R.string.wallet_payout_submitted)
        WalletFeedback.InsufficientBalanceConflict -> stringResource(R.string.wallet_error_conflict_409)
        WalletFeedback.ValidationRejected -> stringResource(R.string.wallet_error_validation_400)
        WalletFeedback.AccountRequired -> stringResource(R.string.wallet_payout_account_required)
        WalletFeedback.NetworkError -> stringResource(R.string.wallet_error_network)
        WalletFeedback.LoadError -> stringResource(R.string.wallet_error_load)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun WithdrawSectionCard(
    walletState: WalletUiState,
    onSelectRail: (PayoutRailDto) -> Unit,
    onDestinationChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onApplyQuickAmount: (Int) -> Unit,
    onRequestConfirmation: () -> Unit
) {
    val currency = walletState.currency
    val minFormatted = MoneyFormatter.formatAmount(walletState.minimumPayoutAmount, currency)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.wallet_request_payout),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.wallet_payout_rails_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(walletState.availableRails) { rail ->
                    FilterChip(
                        selected = walletState.selectedRail == rail,
                        onClick = { onSelectRail(rail) },
                        label = { Text(text = stringResource(railLabelRes(rail))) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val destinationErrorText = walletState.destinationError?.let {
                stringResource(destinationErrorRes(it))
            }
            OutlinedTextField(
                value = walletState.destinationInput,
                onValueChange = onDestinationChange,
                label = { Text(text = stringResource(R.string.wallet_destination_label)) },
                placeholder = { Text(text = stringResource(railHintRes(walletState.selectedRail))) },
                isError = destinationErrorText != null,
                supportingText = {
                    val text = destinationErrorText ?: stringResource(railHintRes(walletState.selectedRail))
                    Text(text = text)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            val amountErrorText = when (val err = walletState.amountError) {
                null -> null
                WalletAmountError.InvalidFormat -> stringResource(R.string.wallet_payout_invalid_amount)
                is WalletAmountError.BelowMinimum -> stringResource(
                    R.string.wallet_payout_below_min,
                    err.formattedMinimum
                )
                WalletAmountError.InsufficientBalance -> stringResource(R.string.wallet_payout_insufficient)
            }
            OutlinedTextField(
                value = walletState.amountInput,
                onValueChange = onAmountChange,
                label = { Text(text = stringResource(R.string.wallet_amount_label, currency)) },
                isError = amountErrorText != null,
                supportingText = {
                    val helper = amountErrorText ?: stringResource(
                        R.string.wallet_min_withdrawal_hint,
                        minFormatted
                    )
                    Text(text = helper)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onApplyQuickAmount(25) },
                    label = { Text(text = stringResource(R.string.wallet_quick_25)) }
                )
                AssistChip(
                    onClick = { onApplyQuickAmount(50) },
                    label = { Text(text = stringResource(R.string.wallet_quick_50)) }
                )
                AssistChip(
                    onClick = { onApplyQuickAmount(100) },
                    label = { Text(text = stringResource(R.string.wallet_quick_max)) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onRequestConfirmation,
                enabled = !walletState.isSubmittingPayout,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (walletState.isSubmittingPayout) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(text = stringResource(R.string.wallet_submitting_payout))
                } else {
                    Text(text = stringResource(R.string.wallet_submit_payout))
                }
            }
        }
    }
}

@Composable
private fun PayoutConfirmationDialog(
    confirmation: PendingPayoutConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val formattedAmount = MoneyFormatter.formatAmount(confirmation.amount, confirmation.currency)
    val railName = stringResource(railLabelRes(confirmation.rail))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.wallet_confirm_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.wallet_confirm_message,
                    formattedAmount,
                    railName,
                    confirmation.maskedDestination
                )
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.wallet_confirm_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.wallet_confirm_cancel))
            }
        }
    )
}

@Composable
private fun LedgerEntryCard(entry: LedgerEntryDto) {
    val isNegative = entry.amount.compareTo(BigDecimal.ZERO) < 0
    val absAmount = entry.amount.abs()
    val formatted = MoneyFormatter.formatAmount(absAmount, entry.currency)
    val signedAmount = if (isNegative) "-$formatted" else "+$formatted"
    val badgeColor = ledgerTypeColor(entry.type)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = badgeColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = stringResource(ledgerTypeLabelRes(entry.type)),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = entry.createdAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = signedAmount,
                style = MaterialTheme.typography.titleMedium,
                color = if (isNegative) MaterialTheme.colorScheme.error else YaytifEmeraldDark
            )
        }
    }
}

@Composable
private fun PayoutRequestCard(payout: PayoutRequestDto) {
    val statusColor = payoutStatusColor(payout.status)
    val maskedDest = PayoutRailValidator.maskDestination(payout.rail, payout.destination)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
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
                    text = stringResource(railLabelRes(payout.rail)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = stringResource(payoutStatusLabelRes(payout.status)),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = maskedDest,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                )

                Text(
                    text = MoneyFormatter.formatAmount(payout.amount, payout.currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = payout.createdAt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyWalletCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@StringRes
private fun tabLabelRes(tab: WalletTab): Int = when (tab) {
    WalletTab.WITHDRAW -> R.string.wallet_tab_withdraw
    WalletTab.LEDGER -> R.string.wallet_tab_ledger
    WalletTab.PAYOUTS -> R.string.wallet_tab_payouts
}

@StringRes
private fun railLabelRes(rail: PayoutRailDto): Int = when (rail) {
    PayoutRailDto.PIX -> R.string.rail_pix
    PayoutRailDto.MERCADO_PAGO -> R.string.rail_mercado_pago
    PayoutRailDto.MPESA -> R.string.rail_mpesa
    PayoutRailDto.PAYSTACK_OPAY -> R.string.rail_paystack_opay
    PayoutRailDto.AIRTIME -> R.string.rail_airtime
    PayoutRailDto.PAYPAL -> R.string.rail_paypal
}

@StringRes
private fun railHintRes(rail: PayoutRailDto): Int = when (rail) {
    PayoutRailDto.PIX -> R.string.wallet_hint_pix
    PayoutRailDto.MERCADO_PAGO -> R.string.wallet_hint_mercado_pago
    PayoutRailDto.MPESA -> R.string.wallet_hint_mpesa
    PayoutRailDto.PAYSTACK_OPAY -> R.string.wallet_hint_paystack_opay
    PayoutRailDto.AIRTIME -> R.string.wallet_hint_airtime
    PayoutRailDto.PAYPAL -> R.string.wallet_hint_paypal
}

@StringRes
private fun destinationErrorRes(reason: DestinationErrorReason): Int = when (reason) {
    DestinationErrorReason.EMPTY -> R.string.wallet_payout_missing_destination
    DestinationErrorReason.INVALID_PIX_KEY -> R.string.wallet_error_pix
    DestinationErrorReason.INVALID_MERCADO_PAGO_DESTINATION -> R.string.wallet_error_mercado_pago
    DestinationErrorReason.INVALID_MPESA_MSISDN -> R.string.wallet_error_mpesa
    DestinationErrorReason.INVALID_PAYSTACK_OPAY_ACCOUNT -> R.string.wallet_error_paystack_opay
    DestinationErrorReason.INVALID_AIRTIME_PHONE -> R.string.wallet_error_airtime
    DestinationErrorReason.INVALID_PAYPAL_EMAIL -> R.string.wallet_error_paypal
}

@StringRes
private fun ledgerTypeLabelRes(type: LedgerEntryTypeDto): Int = when (type) {
    LedgerEntryTypeDto.ATTENTION_TIME -> R.string.ledger_type_attention_time
    LedgerEntryTypeDto.AFFILIATE_CASHBACK -> R.string.ledger_type_affiliate_cashback
    LedgerEntryTypeDto.PAYOUT_WITHDRAWAL -> R.string.ledger_type_payout_withdrawal
    LedgerEntryTypeDto.REFUND -> R.string.ledger_type_refund
}

private fun ledgerTypeColor(type: LedgerEntryTypeDto): Color = when (type) {
    LedgerEntryTypeDto.ATTENTION_TIME -> YaytifEmerald
    LedgerEntryTypeDto.AFFILIATE_CASHBACK -> LedgerCashbackBadgeColor
    LedgerEntryTypeDto.PAYOUT_WITHDRAWAL -> LedgerWithdrawalBadgeColor
    LedgerEntryTypeDto.REFUND -> LedgerRefundBadgeColor
}

@StringRes
private fun payoutStatusLabelRes(status: PayoutStatusDto): Int = when (status) {
    PayoutStatusDto.PENDING -> R.string.payout_status_pending
    PayoutStatusDto.PROCESSING -> R.string.payout_status_processing
    PayoutStatusDto.PAID -> R.string.payout_status_paid
    PayoutStatusDto.FAILED -> R.string.payout_status_failed
}

@Composable
private fun payoutStatusColor(status: PayoutStatusDto): Color = when (status) {
    PayoutStatusDto.PENDING -> PayoutPendingBadgeColor
    PayoutStatusDto.PROCESSING -> PayoutProcessingBadgeColor
    PayoutStatusDto.PAID -> YaytifEmerald
    PayoutStatusDto.FAILED -> MaterialTheme.colorScheme.error
}
