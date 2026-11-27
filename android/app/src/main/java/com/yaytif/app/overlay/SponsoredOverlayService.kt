package com.yaytif.app.overlay

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.yaytif.app.R
import com.yaytif.app.YaytifApplication
import com.yaytif.app.data.repository.ActiveAdSessionState
import com.yaytif.app.data.repository.AdSessionRepository
import com.yaytif.app.domain.AffiliateLinkLauncher
import com.yaytif.app.domain.AttentionStopReason
import com.yaytif.app.domain.AttentionTimerEngine
import com.yaytif.app.domain.MarketConfig
import com.yaytif.app.domain.MoneyFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Non-blocking [WindowManager] floating overlay displaying an active sponsored alert,
 * live verified attention timer, [java.math.BigDecimal] earned total, and `{click_id}` CTA.
 *
 * PRIVACY INVARIANT (.cursorrules):
 * Strictly scoped to sponsored ad sessions. Never accesses local intercepted notifications.
 */
class SponsoredOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var adSessionRepository: AdSessionRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rootView: LinearLayout? = null
    private var accentBar: View? = null
    private var advertiserText: TextView? = null
    private var titleText: TextView? = null
    private var bodyText: TextView? = null
    private var metricsText: TextView? = null
    private var statusText: TextView? = null
    private var ctaButton: Button? = null
    private var dismissButton: Button? = null

    private var screenReceiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    adSessionRepository.setScreenInteractive(false)
                }
                Intent.ACTION_USER_PRESENT -> {
                    adSessionRepository.setScreenInteractive(true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    adSessionRepository.setScreenInteractive(isDeviceInteractiveAndUnlocked())
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val appContainer = (application as YaytifApplication).appContainer
        adSessionRepository = appContainer.adSessionRepository

        if (!canDrawOverlays(this)) {
            stopSelf()
            return
        }

        registerScreenStateReceiver()
        adSessionRepository.setScreenInteractive(isDeviceInteractiveAndUnlocked())
        attachOverlayView()
        adSessionRepository.setAdVisible(true)

        serviceScope.launch {
            combine(
                adSessionRepository.activeSession,
                appContainer.preferencesRepository.preferencesFlow
            ) { session, prefs ->
                session to prefs.localeTag
            }.collect { (session, localeTag) ->
                if (session == null) {
                    stopSelf()
                } else {
                    renderSession(session, localeTag)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlays(this) || adSessionRepository.activeSession.value == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        adSessionRepository.setScreenInteractive(isDeviceInteractiveAndUnlocked())
        adSessionRepository.setAdVisible(true)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::adSessionRepository.isInitialized) {
            adSessionRepository.setAdVisible(false)
        }
        unregisterScreenStateReceiver()
        detachOverlayView()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenStateReceiver) }
        screenReceiverRegistered = false
    }

    private fun isDeviceInteractiveAndUnlocked(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val interactive = powerManager?.isInteractive ?: true
        val locked = keyguardManager?.isKeyguardLocked ?: false
        return interactive && !locked
    }

    private fun attachOverlayView() {
        if (rootView != null) return

        val cardBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#111827"))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.parseColor("#334155"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            elevation = dp(10).toFloat()
            clipToOutline = true
        }

        val topBar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(5)
            )
            setBackgroundColor(OverlayColorParser.DEFAULT_ACCENT_ARGB)
        }
        container.addView(topBar)

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(12), dp(16), dp(14))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val advertiserView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

        val dismissBtn = Button(this).apply {
            text = getString(R.string.overlay_dismiss)
            isAllCaps = false
            setTextColor(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPaddingRelative(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener {
                serviceScope.launch {
                    adSessionRepository.endSession(AttentionStopReason.USER_DISMISSED)
                    stopSelf()
                }
            }
        }

        headerRow.addView(advertiserView)
        headerRow.addView(dismissBtn)
        contentColumn.addView(headerRow)

        val titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        contentColumn.addView(titleView)

        val bodyView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        contentColumn.addView(bodyView)

        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }

        val metricsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        }

        val metricsView = TextView(this).apply {
            setTextColor(OverlayColorParser.DEFAULT_ACCENT_ARGB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

        val statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

        metricsCol.addView(metricsView)
        metricsCol.addView(statusView)

        val ctaBtn = Button(this).apply {
            text = getString(R.string.overlay_open_offer)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPaddingRelative(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                val active = adSessionRepository.activeSession.value ?: return@setOnClickListener
                serviceScope.launch {
                    adSessionRepository.flushNow()
                    AffiliateLinkLauncher.openAffiliateLink(
                        context = this@SponsoredOverlayService,
                        resolvedAffiliateUrl = active.resolvedAffiliateUrl
                    )
                }
            }
        }

        footerRow.addView(metricsCol)
        footerRow.addView(ctaBtn)
        contentColumn.addView(footerRow)
        container.addView(contentColumn)

        val overlayWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(280))
        val params = WindowManager.LayoutParams(
            overlayWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44)
        }

        rootView = container
        accentBar = topBar
        advertiserText = advertiserView
        titleText = titleView
        bodyText = bodyView
        metricsText = metricsView
        statusText = statusView
        ctaButton = ctaBtn
        dismissButton = dismissBtn

        try {
            windowManager.addView(container, params)
        } catch (_: SecurityException) {
            rootView = null
            stopSelf()
        } catch (_: WindowManager.BadTokenException) {
            rootView = null
            stopSelf()
        }
    }

    private fun detachOverlayView() {
        val view = rootView ?: return
        rootView = null
        runCatching { windowManager.removeView(view) }
    }

    private fun renderSession(session: ActiveAdSessionState, localeTag: String) {
        val container = rootView ?: return
        val layoutDirection = if (MarketConfig.isRtlLocale(localeTag)) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
        container.layoutDirection = layoutDirection

        val accentArgb = OverlayColorParser.parsePriorityColorArgb(session.alert.priorityColor)
        val ctaTextColor = if (OverlayColorParser.isDarkColor(accentArgb)) Color.WHITE else Color.parseColor("#022C22")
        val ctaBackground = GradientDrawable().apply {
            setColor(accentArgb)
            cornerRadius = dp(10).toFloat()
        }

        accentBar?.setBackgroundColor(accentArgb)
        advertiserText?.text = session.alert.advertiserName
        titleText?.text = session.alert.title
        bodyText?.text = session.alert.body

        val timer = session.timerState
        val formattedTime = AttentionTimerEngine.formatElapsedSeconds(timer.totalActiveSeconds)
        val formattedEarned = MoneyFormatter.formatAmount(
            amount = timer.liveEstimatedEarnedAmount,
            currencyCode = session.currencyCode
        )
        metricsText?.text = getString(R.string.feed_session_timer_earned, formattedTime, formattedEarned)
        metricsText?.setTextColor(accentArgb)

        statusText?.text = when (timer.stopReason) {
            AttentionStopReason.DAILY_CAP_REACHED -> getString(R.string.feed_session_status_daily_cap)
            AttentionStopReason.CAMPAIGN_BUDGET_EXHAUSTED -> getString(R.string.feed_session_status_budget_exhausted)
            AttentionStopReason.USER_DISMISSED -> getString(R.string.feed_session_status_paused)
            null -> if (timer.isActivelyCounting) {
                getString(R.string.feed_session_status_active)
            } else {
                getString(R.string.feed_session_status_paused)
            }
        }

        ctaButton?.apply {
            background = ctaBackground
            setTextColor(ctaTextColor)
            isEnabled = session.isAffiliateUrlValid
            text = getString(R.string.overlay_open_offer)
        }
        dismissButton?.text = getString(R.string.overlay_dismiss)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun buildOverlayPermissionIntent(context: Context): Intent {
            return Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun start(context: Context): Boolean {
            if (!canDrawOverlays(context)) {
                return false
            }
            val intent = Intent(context, SponsoredOverlayService::class.java)
            context.startService(intent)
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SponsoredOverlayService::class.java))
        }
    }
}
