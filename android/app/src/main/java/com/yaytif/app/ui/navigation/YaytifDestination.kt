package com.yaytif.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.yaytif.app.R

enum class YaytifDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    Feed(
        route = "feed",
        labelRes = R.string.nav_feed,
        icon = Icons.Filled.Campaign
    ),
    Vault(
        route = "vault",
        labelRes = R.string.nav_vault,
        icon = Icons.Filled.Lock
    ),
    Wallet(
        route = "wallet",
        labelRes = R.string.nav_wallet,
        icon = Icons.Filled.AccountBalanceWallet
    ),
    Settings(
        route = "settings",
        labelRes = R.string.nav_settings,
        icon = Icons.Filled.Settings
    )
}
