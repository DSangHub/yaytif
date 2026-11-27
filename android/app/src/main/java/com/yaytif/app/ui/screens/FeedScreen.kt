package com.yaytif.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yaytif.app.R
import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import com.yaytif.app.data.repository.ActiveAdSessionState
import com.yaytif.app.domain.AffiliateLinkLauncher
import com.yaytif.app.domain.AttentionStopReason
import com.yaytif.app.domain.AttentionTimerEngine
import com.yaytif.app.domain.MarketConfig
import com.yaytif.app.domain.MoneyFormatter
import com.yaytif.app.overlay.OverlayColorParser
import com.yaytif.app.overlay.SponsoredOverlayService
import com.yaytif.app.ui.state.SessionActionError

@Composable
fun FeedScreen(
    alerts: List<SponsoredAlertEntity>,
    selectedCategory: String?,
    currencyCode: String,
    isSyncing: Boolean,
    activeSession: ActiveAdSessionState?,
    isStartingSession: Boolean,
    sessionError: SessionActionError?,
    onSelectCategory: (String?) -> Unit,
    onSyncNow: () -> Unit,
    onStartAlertSession: (SponsoredAlertEntity, (ActiveAdSessionState) -> Unit) -> Unit,
    onToggleSessionPause: () -> Unit,
    onFlushSession: () -> Unit,
    onEndSession: () -> Unit,
    onInvalidAffiliateLink: () -> Unit,
    onDismissSessionError: () -> Unit,
    onSetSessionAdVisible: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canDrawOverlays by remember { mutableStateOf(SponsoredOverlayService.canDrawOverlays(context)) }
    var overlayLaunched by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, overlayLaunched) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    canDrawOverlays = SponsoredOverlayService.canDrawOverlays(context)
                    onSetSessionAdVisible(true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (!overlayLaunched) {
                        onSetSessionAdVisible(false)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.feed_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.feed_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onSyncNow,
                    enabled = !isSyncing
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(text = stringResource(R.string.feed_syncing))
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(18.dp)
                        )
                        Text(text = stringResource(R.string.feed_sync_button))
                    }
                }
            }
        }

        if (sessionError != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val messageRes = when (sessionError) {
                            SessionActionError.ACCOUNT_REQUIRED -> R.string.feed_session_account_required
                            SessionActionError.START_FAILED -> R.string.feed_session_start_error
                            SessionActionError.INVALID_AFFILIATE_LINK -> R.string.feed_session_invalid_link
                        }
                        Text(
                            text = stringResource(messageRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        TextButton(onClick = onDismissSessionError) {
                            Text(
                                text = stringResource(R.string.overlay_dismiss),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        if (activeSession != null) {
            item(key = "active-session-${activeSession.sessionId}") {
                ActiveSessionCard(
                    session = activeSession,
                    canDrawOverlays = canDrawOverlays,
                    onTogglePause = onToggleSessionPause,
                    onEndSession = {
                        overlayLaunched = false
                        SponsoredOverlayService.stop(context)
                        onEndSession()
                    },
                    onOpenAffiliateOffer = {
                        onFlushSession()
                        val launched = AffiliateLinkLauncher.openAffiliateLink(
                            context = context,
                            resolvedAffiliateUrl = activeSession.resolvedAffiliateUrl
                        )
                        if (!launched) {
                            onInvalidAffiliateLink()
                        }
                    },
                    onLaunchOrRequestOverlay = {
                        if (SponsoredOverlayService.canDrawOverlays(context)) {
                            canDrawOverlays = true
                            overlayLaunched = SponsoredOverlayService.start(context)
                        } else {
                            context.startActivity(
                                SponsoredOverlayService.buildOverlayPermissionIntent(context)
                            )
                        }
                    }
                )
            }
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { onSelectCategory(null) },
                        label = { Text(text = stringResource(R.string.feed_all_categories)) }
                    )
                }
                items(MarketConfig.AVAILABLE_CATEGORIES) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            onSelectCategory(if (selectedCategory == category) null else category)
                        },
                        label = { Text(text = stringResource(categoryLabelRes(category))) }
                    )
                }
            }
        }

        if (alerts.isEmpty()) {
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
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Campaign,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.feed_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.feed_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(alerts, key = { it.id }) { alert ->
                SponsoredAlertCard(
                    alert = alert,
                    currencyCode = currencyCode,
                    isActive = activeSession?.alert?.id == alert.id,
                    isStarting = isStartingSession,
                    onClick = {
                        onStartAlertSession(alert) { _ ->
                            if (SponsoredOverlayService.canDrawOverlays(context)) {
                                canDrawOverlays = true
                                overlayLaunched = SponsoredOverlayService.start(context)
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveSessionCard(
    session: ActiveAdSessionState,
    canDrawOverlays: Boolean,
    onTogglePause: () -> Unit,
    onEndSession: () -> Unit,
    onOpenAffiliateOffer: () -> Unit,
    onLaunchOrRequestOverlay: () -> Unit
) {
    val timer = session.timerState
    val accentColor = Color(OverlayColorParser.parsePriorityColorArgb(session.alert.priorityColor))
    val formattedTime = AttentionTimerEngine.formatElapsedSeconds(timer.totalActiveSeconds)
    val formattedEarned = MoneyFormatter.formatAmount(
        amount = timer.liveEstimatedEarnedAmount,
        currencyCode = session.currencyCode
    )

    val statusRes = when (timer.stopReason) {
        AttentionStopReason.DAILY_CAP_REACHED -> R.string.feed_session_status_daily_cap
        AttentionStopReason.CAMPAIGN_BUDGET_EXHAUSTED -> R.string.feed_session_status_budget_exhausted
        AttentionStopReason.USER_DISMISSED -> R.string.feed_session_status_paused
        null -> if (timer.isActivelyCounting) {
            R.string.feed_session_status_active
        } else {
            R.string.feed_session_status_paused
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Text(
                        text = stringResource(R.string.feed_active_session_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Text(
                    text = stringResource(
                        R.string.feed_session_timer_earned,
                        formattedTime,
                        formattedEarned
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = session.alert.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(statusRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenAffiliateOffer,
                    enabled = session.isAffiliateUrlValid
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(16.dp)
                    )
                    Text(text = stringResource(R.string.feed_session_open_offer))
                }

                OutlinedButton(onClick = onLaunchOrRequestOverlay) {
                    Icon(
                        imageVector = Icons.Filled.Layers,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(16.dp)
                    )
                    Text(
                        text = stringResource(
                            if (canDrawOverlays) {
                                R.string.feed_session_show_overlay
                            } else {
                                R.string.feed_session_enable_overlay
                            }
                        )
                    )
                }

                if (!timer.isStopped) {
                    OutlinedButton(onClick = onTogglePause) {
                        Icon(
                            imageVector = if (timer.isUserPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(16.dp)
                        )
                        Text(
                            text = stringResource(
                                if (timer.isUserPaused) {
                                    R.string.feed_session_resume
                                } else {
                                    R.string.feed_session_pause
                                }
                            )
                        )
                    }
                }

                TextButton(onClick = onEndSession) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(16.dp)
                    )
                    Text(text = stringResource(R.string.feed_session_stop))
                }
            }
        }
    }
}

@Composable
private fun SponsoredAlertCard(
    alert: SponsoredAlertEntity,
    currencyCode: String,
    isActive: Boolean,
    isStarting: Boolean,
    onClick: () -> Unit
) {
    val accent = Color(OverlayColorParser.parsePriorityColorArgb(alert.priorityColor))
    val formattedPayout = MoneyFormatter.formatAmount(alert.payoutPerMinute, currencyCode)
    val formattedCommission = MoneyFormatter.formatCommissionPercent(alert.commissionShare)

    Card(
        onClick = onClick,
        enabled = !isStarting,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Text(
                        text = alert.advertiserName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.feed_payout_per_minute, formattedPayout),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = alert.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = alert.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(categoryLabelRes(alert.category)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.feed_commission_share, formattedCommission),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.feed_tap_to_earn),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@StringRes
fun categoryLabelRes(category: String): Int = when (category) {
    "ecommerce" -> R.string.category_ecommerce
    "fintech" -> R.string.category_fintech
    "food_delivery" -> R.string.category_food_delivery
    "telecom" -> R.string.category_telecom
    "entertainment" -> R.string.category_entertainment
    "travel" -> R.string.category_travel
    else -> R.string.category_ecommerce
}
