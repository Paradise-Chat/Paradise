package com.gigiaj.paradise

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.getcapacitor.BridgeActivity
import com.getcapacitor.BridgeWebViewClient
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.lang.ref.WeakReference
import java.util.Locale

class MainActivity : BridgeActivity() {

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var currentWebViewRef: WeakReference<ParadiseWebView>? = null

        fun notifyCallVisibilityChanged() {
            mainHandler.post {
                currentWebViewRef?.get()?.syncCallVisibilityState()
            }
        }
    }

    private var showingIncomingLockOverlay: Boolean = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        registerPlugin(ShadowDevicePlugin::class.java)
        registerPlugin(ParadiseProxyPlugin::class.java)
        registerPlugin(ParadiseCallPlugin::class.java)
        registerPlugin(ParadiseUIPlugin::class.java)
        super.onCreate(savedInstanceState)

        setShowWhenLocked(false)
        setTurnScreenOn(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }

        applyThemeChrome(
            bgColor = 0xFF313338.toInt(),
            statusColor = 0xFF1E1F22.toInt(),
            isDark = true
        )

        val activeBridge = bridge
        val wv = activeBridge?.webView
        wv?.settings?.mediaPlaybackRequiresUserGesture = false
        wv?.settings?.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        if (wv != null) {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
        }
        if (activeBridge != null) {
            activeBridge.setWebViewClient(object : BridgeWebViewClient(activeBridge) {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    if (request != null && !request.isForMainFrame) {
                        val scheme = request.url?.scheme?.lowercase(Locale.US)
                        if (scheme == "http" || scheme == "https") {
                            return false
                        }
                    }
                    return super.shouldOverrideUrlLoading(view, request)
                }
            })
        }
        if (wv is ParadiseWebView) {
            currentWebViewRef = WeakReference(wv)
        }

        handleCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        clearMessageNotificationsPreservingCalls()
    }

    override fun onPause() {
        super.onPause()
        if (showingIncomingLockOverlay) {
            showingIncomingLockOverlay = false
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
        keepWebViewAliveDuringCall()
    }

    override fun onStop() {
        super.onStop()
        keepWebViewAliveDuringCall()
    }

    override fun onDestroy() {
        val wv = bridge?.webView
        if (currentWebViewRef?.get() === wv) {
            currentWebViewRef = null
        }
        super.onDestroy()
    }

    private fun keepWebViewAliveDuringCall() {
        if (!ParadiseForegroundService.isCallActive) return
        val wv = bridge?.webView ?: return
        wv.onResume()
        wv.resumeTimers()
        if (wv is ParadiseWebView) {
            wv.syncCallVisibilityState()
        }
    }

    private fun clearMessageNotificationsPreservingCalls() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        for (active in nm.activeNotifications) {
            if (active.id != ParadiseForegroundService.ONGOING_CALL_NOTIFICATION_ID &&
                active.id != ParadiseForegroundService.INCOMING_CALL_NOTIFICATION_ID
            ) {
                nm.cancel(active.tag, active.id)
            }
        }
    }

    private fun handleCallIntent(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        val action = incomingIntent.action
        val legacyAction = incomingIntent.getStringExtra("action")
        val incomingRingRoom = incomingIntent.getStringExtra("incomingCallRoomId")

        if (!incomingRingRoom.isNullOrBlank() && action != ParadiseForegroundService.ACTION_ANSWER_CALL) {
            showingIncomingLockOverlay = true
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            incomingIntent.removeExtra("incomingCallRoomId")
            return
        }

        if (action == ParadiseForegroundService.ACTION_ANSWER_CALL || legacyAction == "answerCall") {
            showingIncomingLockOverlay = false
            setShowWhenLocked(false)
            setTurnScreenOn(false)

            val roomId = incomingIntent.getStringExtra("roomId")
            val callerName = incomingIntent.getStringExtra("callerName") ?: "Paradise Call"
            val engine = incomingIntent.getStringExtra("engine")
            val hasVideo = incomingIntent.getBooleanExtra("hasVideo", false)

            if (!roomId.isNullOrBlank()) {
                ParadiseForegroundService.cancelIncomingCallNotification(this, roomId)
                ParadiseForegroundService.startOrUpdate(
                    context = this,
                    roomId = roomId,
                    callerName = callerName,
                    hasVideo = hasVideo,
                    engine = engine
                )
                ParadiseCallPlugin.deliverOrQueueAnswer(roomId, engine, hasVideo)
            }

            incomingIntent.action = null
            incomingIntent.removeExtra("action")
            incomingIntent.removeExtra("roomId")
        }
    }

    @Suppress("DEPRECATION")
    internal fun applyThemeChrome(bgColor: Int, statusColor: Int, isDark: Boolean) {
        val desiredNightMode = if (isDark) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != desiredNightMode) {
            AppCompatDelegate.setDefaultNightMode(desiredNightMode)
        }
        if (delegate.localNightMode != desiredNightMode) {
            delegate.localNightMode = desiredNightMode
        }

        val win = window ?: return
        win.decorView.setBackgroundColor(bgColor)
        bridge?.webView?.setBackgroundColor(bgColor)
        win.navigationBarColor = bgColor
        win.statusBarColor = statusColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            win.isNavigationBarContrastEnforced = false
        }
        val controller = WindowCompat.getInsetsController(win, win.decorView)
        controller.isAppearanceLightNavigationBars = !isDark
        controller.isAppearanceLightStatusBars = !isDark
    }
}

@CapacitorPlugin(name = "ParadiseUI")
class ParadiseUIPlugin : Plugin() {

    companion object {
        private const val DEFAULT_BG_COLOR = 0xFF313338.toInt()
        private const val DEFAULT_STATUS_COLOR = 0xFF1E1F22.toInt()

        internal fun parseCssColor(raw: String?): Int? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("#")) {
                return try {
                    val hex = trimmed.substring(1)
                    when (hex.length) {
                        3 -> {
                            val r = hex[0].toString().repeat(2)
                            val g = hex[1].toString().repeat(2)
                            val b = hex[2].toString().repeat(2)
                            Color.parseColor("#FF$r$g$b")
                        }
                        6 -> Color.parseColor("#FF$hex")
                        8 -> {
                            val rrggbb = hex.substring(0, 6)
                            val aa = hex.substring(6, 8)
                            Color.parseColor("#$aa$rrggbb")
                        }
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (trimmed.lowercase(Locale.US).startsWith("rgb")) {
                val open = trimmed.indexOf('(')
                val close = trimmed.lastIndexOf(')')
                if (open in 0 until close) {
                    val parts = trimmed.substring(open + 1, close).split(',').map { it.trim() }
                    if (parts.size >= 3) {
                        val r = parts[0].toDoubleOrNull()?.toInt()?.coerceIn(0, 255) ?: return null
                        val g = parts[1].toDoubleOrNull()?.toInt()?.coerceIn(0, 255) ?: return null
                        val b = parts[2].toDoubleOrNull()?.toInt()?.coerceIn(0, 255) ?: return null
                        val a = if (parts.size >= 4) (parts[3].toDoubleOrNull() ?: 1.0) else 1.0
                        if (a <= 0.01) return null
                        return Color.rgb(r, g, b)
                    }
                }
            }
            return null
        }
    }

    @PluginMethod
    fun syncTheme(call: PluginCall) {
        val bgColor = parseCssColor(call.getString("color")) ?: DEFAULT_BG_COLOR
        val statusColor = parseCssColor(call.getString("statusColor")) ?: bgColor.takeIf { it != DEFAULT_BG_COLOR } ?: DEFAULT_STATUS_COLOR
        val isDark = call.getBoolean("dark") ?: true

        val hostActivity = activity as? MainActivity
        if (hostActivity == null || hostActivity.isFinishing) {
            call.resolve(JSObject().apply { put("ok", false) })
            return
        }

        hostActivity.runOnUiThread {
            try {
                hostActivity.applyThemeChrome(bgColor, statusColor, isDark)
                call.resolve(JSObject().apply { put("ok", true) })
            } catch (e: Exception) {
                call.reject("Failed to sync native theme chrome: ${e.message}", e)
            }
        }
    }
}
