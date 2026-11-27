# Yaytif Android — Google Play Store Compliance & Policy Guide

This document maps the Yaytif Android architecture (`com.yaytif.app`) directly to Google Play Store Data Safety disclosures, sensitive Android permission justifications, in-app prominent disclosure copy, and pre-submission QA checks.

---

## 1. Strict On-Device Privacy Architecture

Yaytif enforces a strict architectural boundary between personal device notifications and server-synced reward metadata:

- **Personal Intercepted Notifications (Local-Only):** Stored exclusively in the local Room SQLite database (`yaytif_local.db`, table `local_notifications`). `LocalNotificationRepository` has zero network dependencies. Personal notification titles, bodies, sender names, and third-party package names are **never** serialized into Retrofit DTOs, logged in release builds, or sent to `api.yaytif.com`.
- **Cloud Backup & Device Transfer Exclusion:** `AndroidManifest.xml` sets `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, and `android:fullBackupContent="@xml/backup_rules"`, explicitly excluding `database`, `sharedpref`, `file`, `root`, and `external` domains from both Google Drive cloud backup and device-to-device transfer.
- **Pseudonymous Phone Identity:** Registration (`POST /api/v1/users/register`) transmits only a one-way cryptographic `phoneHash` (SHA-256), never a raw phone number, alongside user-selected `countryCode`, `locale`, `currency`, and `interests`.
- **Automated Enforcement:** `PrivacyBoundaryTest`, `ReleaseHardeningAndPrivacyAuditTest`, and `android/scripts/verify-i18n.py` run on every CI build to fail immediately if any personal notification field appears in `data/remote/` or if backup exclusions regress.

---

## 2. Google Play Data Safety Form Mapping

| Data Safety Section | Data Type | Collected? | Shared with 3rd Parties? | Purpose & Notes |
| --- | --- | --- | --- | --- |
| **Personal info** | User IDs (`userId`, SHA-256 `phoneHash`) | Yes | No | Account management, fraud prevention, wallet ledger attribution. Raw phone number is hashed before transmission. |
| **Personal info** | Name, Email, Address, Raw Phone Number | No (optional only when user enters a payout destination like Pix key / PayPal email) | Payment processors only when user submits a payout | Payout destination is entered explicitly by the user per withdrawal request (`POST /api/v1/payouts`) to settle via local rails (`PIX`, `MERCADO_PAGO`, `MPESA`, `PAYSTACK_OPAY`, `AIRTIME`, `PAYPAL`). |
| **Messages / Notifications** | Third-party notification titles, bodies, sender names, app labels | **No** | **No** | Strictly on-device in Room `local_notifications`. Never leaves the device; never uploaded or backed up. |
| **Financial info** | Wallet ledger entries, payout requests & transaction references | Yes | Payout rail settlement partners | Double-entry wallet accounting (`ATTENTION_TIME`, `AFFILIATE_CASHBACK`, `PAYOUT_WITHDRAWAL`, `REFUND`) and local payout execution. |
| **App activity** | Verified sponsored alert attention heartbeats (`activeSecondsDelta`), affiliate `clickId` | Yes | Affiliate network postback attribution (pseudonymous `clickId` only) | Computing verified attention rewards and 50% affiliate cashback share. |
| **App info & preferences** | Country code, locale (13 supported), currency, self-selected interest tags | Yes | No | Serving localized sponsored alert campaigns and selecting regional payout rails. |
| **Location / Contacts / Photos / Audio / Files** | Precise or approximate GPS, Contacts, Media | **No** | **No** | Not requested in `AndroidManifest.xml` and never collected. |

### Data Safety Security Declarations
- **Is all user data encrypted in transit?** **Yes** — Release builds enforce HTTPS (`https://api.yaytif.com/`) with TLS 1.2+.
- **Do you provide a way for users to request that their data be deleted?** **Yes** — Users can immediately purge their on-device notification vault in **Settings → Clear local notification vault**, and request server account/wallet deletion via `https://yaytif.com/privacy/delete-account`.
- **Independent privacy/backup posture:** Local notification data is excluded from Android Auto Backup and D2D transfer (`data_extraction_rules.xml`, `backup_rules.xml`).

---

## 3. Sensitive Permission Declarations & Prominent Disclosure Copy

### 3.1 `BIND_NOTIFICATION_LISTENER_SERVICE` (Notification Access)

- **Play Policy Core Functionality Justification:** Yaytif's Private Vault organizes and deduplicates a user's incoming notifications on-device so users can review, mark read, archive, and silence clutter in one unified, privacy-preserving inbox.
- **Strict Scope & Non-Transmission:** Intercepted notifications are persisted solely in Room `local_notifications`. Yaytif never reads OTP/banking messages for server analytics, never uploads notification text/senders/app names, and never shares intercepted notification data with advertisers.
- **Required Prominent Disclosure Copy (shown before navigating to `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`):**

> **Allow Notification Access for Your Private Vault**
>
> Yaytif uses Android Notification Access to organize your incoming notifications inside your **Private Vault**.
>
> - **100% On-Device:** Your personal notification titles, message bodies, and sender names stay strictly in encrypted local storage on this phone.
> - **Never Uploaded:** Yaytif never transmits, logs, or shares your personal notifications with our servers or advertisers.
> - **Full Control:** You can clear your local vault or revoke Notification Access at any time in Android Settings.
>
> `[Not Now]`  `[Continue to Settings]`

### 3.2 `SYSTEM_ALERT_WINDOW` (Display Over Other Apps)

- **Play Policy Core Functionality Justification:** Yaytif optionally displays a lightweight, user-initiated floating alert card (`SponsoredOverlayService`) when a user opts in to receiving non-intrusive sponsored reward alerts matched to their chosen interest tags and local market.
- **Compliance Guardrails:**
  - Strictly opt-in; never enabled without explicit user consent and prominent disclosure.
  - Overlay is compact, clearly labeled as **Sponsored**, single-tap dismissible, and never obscures system permission prompts, lock screens, or security dialogs (`FLAG_NOT_FOCUSABLE` / non-blocking layout).
  - Attention-time heartbeats only accrue while the user actively engages with the sponsored alert.
- **Required Prominent Disclosure Copy (shown before navigating to `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`):**

> **Allow Optional Sponsored Alert Overlays**
>
> Yaytif requests **Display over other apps** permission only if you want to view optional, clearly labeled sponsored reward cards without switching apps.
>
> - Dismiss any alert with a single tap or disable overlays at any time in Settings.
> - Overlays never block system dialogs or capture input from other apps.
> - Only active viewing time on Yaytif sponsored offers is measured to credit your wallet.
>
> `[Skip]`  `[Enable Overlays]`

### 3.3 Play Console Review Screencast Checklist
When submitting a release that declares `BIND_NOTIFICATION_LISTENER_SERVICE` or `SYSTEM_ALERT_WINDOW`, record a 30–60 second unlisted screen recording showing:
1. Launching Yaytif and viewing the Prominent Disclosure modal before Android System Settings opens.
2. Granting the permission and returning to the Private Vault / Sponsored Feed.
3. Demonstrating immediate opt-out / **Clear local notification vault** in Settings.

---

## 4. Pre-Submission QA Checklist

- [ ] **Verification Script:** `python3 android/scripts/verify-i18n.py` passes with 0 errors (13 locales × 60 keys, XML well-formedness, privacy boundary, 0 `TODO`/`FIXME`, 0 committed secrets).
- [ ] **Unit Tests & Lint:** `./gradlew testDebugUnitTest lintDebug --no-daemon` passes with 0 failures and 0 lint errors.
- [ ] **R8 Minified Release Build:** `./gradlew bundleRelease --no-daemon` builds with `isMinifyEnabled = true`, `isShrinkResources = true`, and produces `app/build/outputs/mapping/release/mapping.txt`.
- [ ] **AAB Signature Verification:** `jarsigner -verify -verbose -certs` confirms valid upload key signature on `app-release.aab`.
- [ ] **13-Locale & Arabic RTL Spot Check:** Launch on an Android 13+ and Android 10–12 device/emulator, switch language to `العربية (ar)` in Settings, and verify mirrored RTL layout across Feed, Vault, Wallet, and Settings.
- [ ] **Regional Payout Rails & `BigDecimal` Accuracy:** Verify Brazil (`PIX`), LATAM (`MERCADO_PAGO`), Kenya/Tanzania (`MPESA`), Nigeria (`PAYSTACK_OPAY`), Africa (`AIRTIME`), and Global (`PAYPAL`) display accurate currency symbols, minimum payout thresholds, and exact decimal validation.
- [ ] **Network Privacy Audit:** Confirm via proxy/logcat on a release build that zero `android.util.Log.d/v/i` statements are emitted and zero personal notification content appears in any HTTP payload.
- [ ] **Play Console Metadata:** Upload `mapping.txt`, confirm `targetSdk = 35`, verify Play Data Safety form matches Section 2 above, and attach permission screencast link if requested.
