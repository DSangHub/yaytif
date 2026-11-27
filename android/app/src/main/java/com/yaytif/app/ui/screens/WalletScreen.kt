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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yaytif.app.R
import com.yaytif.app.data.remote.dto.PayoutRailDto
import com.yaytif.app.domain.MarketConfig
import com.yaytif.app.domain.MoneyFormatter
import com.yaytif.app.ui.state.PayoutFeedback
import com.yaytif.app.ui.state.WalletUiState

@Composable
fun WalletScreen(
    walletState: WalletUiState,
    countryCode: String,
    currencyCode: String,
    onSubmitPayout: (PayoutRailDto, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val availableRails = remember(countryCode) {
        MarketConfig.payoutRailsForCountry(countryCode)
    }
    var selectedRail by remember(countryCode) {
        mutableStateOf(availableRails.first())
    }
    var destination by remember { mutableStateOf("") }
    var amountInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.wallet_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
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
                        text = MoneyFormatter.formatAmount(walletState.walletBalance, currencyCode),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.wallet_daily_earned_label,
                            MoneyFormatter.formatAmount(walletState.dailyTimeEarned, currencyCode)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item {
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
                        items(availableRails) { rail ->
                            FilterChip(
                                selected = selectedRail == rail,
                                onClick = { selectedRail = rail },
                                label = { Text(text = stringResource(railLabelRes(rail))) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        label = { Text(text = stringResource(R.string.wallet_destination_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text(text = stringResource(R.string.wallet_amount_label, currencyCode)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )

                    walletState.payoutFeedback?.let { feedback ->
                        Spacer(modifier = Modifier.height(8.dp))
                        val feedbackText = when (feedback) {
                            PayoutFeedback.Submitted -> stringResource(R.string.wallet_payout_submitted)
                            PayoutFeedback.MissingAccount -> stringResource(R.string.wallet_payout_account_required)
                            PayoutFeedback.MissingDestination -> stringResource(R.string.wallet_payout_missing_destination)
                            PayoutFeedback.InvalidAmount -> stringResource(R.string.wallet_payout_invalid_amount)
                            is PayoutFeedback.BelowMinimum -> stringResource(
                                R.string.wallet_payout_below_min,
                                feedback.formattedMinimum
                            )
                            PayoutFeedback.InsufficientBalance -> stringResource(R.string.wallet_payout_insufficient)
                            is PayoutFeedback.NetworkError -> feedback.message
                        }
                        val feedbackColor = if (feedback == PayoutFeedback.Submitted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                        Text(
                            text = feedbackText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = feedbackColor
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { onSubmitPayout(selectedRail, destination, amountInput) },
                        enabled = !walletState.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.wallet_submit_payout))
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.wallet_ledger_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (walletState.ledgerEntries.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text(
                        text = stringResource(R.string.wallet_ledger_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(walletState.ledgerEntries, key = { it.id }) { entry ->
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
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = entry.type.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = entry.createdAt,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = MoneyFormatter.formatAmount(entry.amount, entry.currency),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
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
