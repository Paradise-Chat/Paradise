package com.gigiaj.paradise

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

@CapacitorPlugin(name = "ParadiseProxy")
class ParadiseProxyPlugin : Plugin() {

    companion object {
        private const val DEFAULT_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MAX_REDIRECTS = 6
        private const val MAX_ROUTE_GRANTS = 256
        private val SAFE_REDIRECT_HEADERS = setOf(
            "accept",
            "accept-language",
            "accept-encoding",
            "user-agent",
            "range",
            "if-range"
        )
        private val FORBIDDEN_FRAMING_HEADERS = setOf(
            "content-length",
            "transfer-encoding",
            "host"
        )
    }

    private enum class RouteKind { STREAM, FRAME, EMBED }

    private data class RouteGrant(
        val kind: RouteKind,
        val allowedOrigin: String,
        val headers: Map<String, String>,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamHeaderRules = ConcurrentHashMap<String, Map<String, String>>()
    private val frameHeaderRules = ConcurrentHashMap<String, Map<String, String>>()
    private val routeGrants = ConcurrentHashMap<String, RouteGrant>()
    private val secureRandom = SecureRandom()

    @Volatile
    private var streamServerSocket: ServerSocket? = null

    @Volatile
    private var streamServerSocketV6: ServerSocket? = null

    @Volatile
    private var streamServerPort: Int = 0

    private var authDialog: Dialog? = null
    private var authWebView: WebView? = null
    private var authTitleView: TextView? = null
    private var pendingAuthCall: PluginCall? = null
    private var pendingAuthMode: String = "window"

    private fun settlePendingAuthCall(payload: JSObject) {
        val pending = pendingAuthCall
        pendingAuthCall = null
        pending?.resolve(payload)
    }

    private fun newGrantToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun registerRouteGrant(kind: RouteKind, origin: String, headers: Map<String, String>): String {
        if (routeGrants.size >= MAX_ROUTE_GRANTS) {
            val oldest = routeGrants.entries.minByOrNull { it.value.createdAtMs }?.key
            if (oldest != null) {
                routeGrants.remove(oldest)
            }
        }
        val token = newGrantToken()
        routeGrants[token] = RouteGrant(kind, origin, headers.toMap())
        return token
    }

    private fun effectivePort(url: URL): Int {
        if (url.port > 0) return url.port
        return if (url.protocol.equals("https", ignoreCase = true)) 443 else 80
    }

    private fun extractExactOrigin(url: URL): String {
        val scheme = url.protocol.lowercase(Locale.US)
        val host = url.host.lowercase(Locale.US)
        val port = effectivePort(url)
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun sanitizeHeaderValue(raw: String): String {
        return raw.replace("\r", "").replace("\n", "")
    }

    private fun sanitizeHeaderMap(hdrObj: JSObject?): Map<String, String> {
        val hdrMap = mutableMapOf<String, String>()
        if (hdrObj == null) return hdrMap
        val keys = hdrObj.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            if (hdrObj.isNull(rawKey)) continue
            val k = rawKey.trim()
            val v = hdrObj.optString(rawKey, "")
            if (k.isEmpty() || k.contains('\r') || k.contains('\n') || v.contains('\r') || v.contains('\n')) {
                continue
            }
            val lower = k.lowercase(Locale.US)
            if (FORBIDDEN_FRAMING_HEADERS.contains(lower)) continue
            val existing = hdrMap.keys.firstOrNull { it.equals(k, ignoreCase = true) }
            if (existing != null) hdrMap.remove(existing)
            hdrMap[k] = v
        }
        return hdrMap
    }

    private fun hasCredentialOrCustomHeaders(headers: Map<String, String>): Boolean {
        return headers.keys.any { k ->
            val lower = k.lowercase(Locale.US)
            lower !in SAFE_REDIRECT_HEADERS &&
                lower != "content-type" &&
                lower != "referer" &&
                lower != "origin"
        }
    }

    private fun stripCrossOriginHeaders(headers: MutableMap<String, String>) {
        val iter = headers.keys.iterator()
        while (iter.hasNext()) {
            val hdr = iter.next()
            if (hdr.lowercase(Locale.US) !in SAFE_REDIRECT_HEADERS) {
                iter.remove()
            }
        }
    }

    private fun allowedCorsOrigin(clientOrigin: String?): String? {
        val raw = clientOrigin?.trim().orEmpty()
        if (raw.isEmpty() || raw.contains('\r') || raw.contains('\n')) return null
        val lower = raw.lowercase(Locale.US)
        val loopbackOrigin = if (streamServerPort > 0) "http://localhost:$streamServerPort" else null
        return when {
            lower == "https://localhost" ||
                lower == "http://localhost" ||
                lower == "capacitor://localhost" ||
                (loopbackOrigin != null && lower == loopbackOrigin) -> raw
            else -> null
        }
    }

    @PluginMethod
    fun isSupported(call: PluginCall) {
        val ret = JSObject().apply {
            put("supported", true)
            put("provider", "capacitor")
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun request(call: PluginCall) {
        val rawUrl = call.getString("url")?.trim().orEmpty()
        if (rawUrl.isEmpty()) {
            call.reject("Missing request url")
            return
        }

        val initialMethod = (call.getString("method") ?: "GET").trim().uppercase(Locale.US)
        val headersObj = call.getObject("headers")
        val bodyStr = call.getString("body")
        val bodyEncoding = (call.getString("bodyEncoding") ?: "utf8").lowercase(Locale.US)

        val headersMap = sanitizeHeaderMap(headersObj).toMutableMap()

        val initialBodyBytes: ByteArray? = if (
            bodyStr != null && initialMethod != "GET" && initialMethod != "HEAD"
        ) {
            try {
                if (bodyEncoding == "base64") {
                    Base64.decode(bodyStr, Base64.DEFAULT)
                } else {
                    bodyStr.toByteArray(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                call.reject("Invalid request body encoding: ${e.message}", e)
                return
            }
        } else {
            null
        }

        ioScope.launch {
            try {
                val result = executeHttp(rawUrl, initialMethod, headersMap, initialBodyBytes)
                call.resolve(result)
            } catch (e: Exception) {
                call.reject(e.message ?: "Network request failed", e)
            }
        }
    }

    private fun executeHttp(
        startUrl: String,
        startMethod: String,
        initialHeaders: Map<String, String>,
        initialBody: ByteArray?
    ): JSObject {
        var currentUrl = URL(startUrl)
        if (currentUrl.protocol != "http" && currentUrl.protocol != "https") {
            throw IllegalArgumentException("Unsupported protocol: ${currentUrl.protocol}")
        }

        var currentMethod = startMethod
        var currentBody = initialBody
        val currentHeaders = initialHeaders.toMutableMap()

        for (hop in 0..MAX_REDIRECTS) {
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = currentMethod
            }

            try {
                var hasAcceptEncoding = false
                var hasUserAgent = false
                var hasAcceptLanguage = false
                for ((k, v) in currentHeaders) {
                    if (k.equals("Accept-Encoding", ignoreCase = true)) {
                        hasAcceptEncoding = true
                    } else if (k.equals("User-Agent", ignoreCase = true)) {
                        hasUserAgent = true
                    } else if (k.equals("Accept-Language", ignoreCase = true)) {
                        hasAcceptLanguage = true
                    }
                    conn.setRequestProperty(k, v)
                }
                if (!hasUserAgent) {
                    conn.setRequestProperty("User-Agent", DEFAULT_DESKTOP_USER_AGENT)
                }
                if (!hasAcceptLanguage) {
                    conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }
                if (!hasAcceptEncoding) {
                    conn.setRequestProperty("Accept-Encoding", "gzip")
                }

                if (currentBody != null && currentMethod != "GET" && currentMethod != "HEAD") {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(currentBody.size)
                    conn.outputStream.use { out ->
                        out.write(currentBody)
                        out.flush()
                    }
                }

                val status = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (status in listOf(301, 302, 303, 307, 308) && !location.isNullOrBlank() && hop < MAX_REDIRECTS) {
                    val nextUrl = URL(currentUrl, location)
                    if (nextUrl.protocol != "http" && nextUrl.protocol != "https") {
                        throw IllegalArgumentException("Unsupported redirect protocol: ${nextUrl.protocol}")
                    }
                    val sameOrigin = extractExactOrigin(nextUrl) == extractExactOrigin(currentUrl)
                    if (currentUrl.protocol.equals("https", ignoreCase = true) &&
                        nextUrl.protocol.equals("http", ignoreCase = true) &&
                        hasCredentialOrCustomHeaders(currentHeaders)
                    ) {
                        throw SecurityException("Blocked insecure HTTPS to HTTP credentialed redirect")
                    }
                    if (status == 303 || ((status == 301 || status == 302) && currentMethod == "POST")) {
                        currentMethod = "GET"
                        currentBody = null
                        val iter = currentHeaders.keys.iterator()
                        while (iter.hasNext()) {
                            val hdr = iter.next()
                            if (hdr.equals("Content-Type", ignoreCase = true) ||
                                hdr.equals("Content-Length", ignoreCase = true)
                            ) {
                                iter.remove()
                            }
                        }
                    }
                    if (!sameOrigin) {
                        if ((status == 307 || status == 308) && currentBody != null) {
                            throw SecurityException("Blocked cross-origin 307/308 redirect with request body")
                        }
                        stripCrossOriginHeaders(currentHeaders)
                    }
                    currentUrl = nextUrl
                    continue
                }

                val rawStream: InputStream? = try {
                    if (status >= 400) conn.errorStream else conn.inputStream
                } catch (_: Exception) {
                    conn.errorStream
                }

                val isGzip = "gzip".equals(conn.contentEncoding, ignoreCase = true)
                val responseBytes: ByteArray = if (rawStream == null || currentMethod == "HEAD") {
                    ByteArray(0)
                } else {
                    val decodedStream = if (isGzip) GZIPInputStream(rawStream) else BufferedInputStream(rawStream)
                    decodedStream.use { readAllBytesCompat(it) }
                }

                val respHeaders = JSObject()
                for ((key, values) in conn.headerFields) {
                    if (key == null || values == null) continue
                    val lower = key.lowercase(Locale.US)
                    if (isGzip && (lower == "content-encoding" || lower == "content-length")) {
                        continue
                    }
                    respHeaders.put(lower, values.joinToString(", "))
                }

                val encodedData = Base64.encodeToString(responseBytes, Base64.NO_WRAP)
                return JSObject().apply {
                    put("ok", status in 200..299)
                    put("status", status)
                    put("url", currentUrl.toString())
                    put("headers", respHeaders)
                    put("data", encodedData)
                }
            } finally {
                conn.disconnect()
            }
        }

        throw IllegalStateException("Too many HTTP redirects")
    }

    private fun readAllBytesCompat(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @PluginMethod
    fun openExternal(call: PluginCall) {
        val rawUrl = call.getString("url")?.trim().orEmpty()
        val parsed = validateHttpUrl(rawUrl)
        if (parsed == null) {
            call.reject("Invalid or unsupported external URL")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(parsed.toString())).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            call.resolve(JSObject().apply { put("ok", true) })
        } catch (e: Exception) {
            call.reject("Failed to open external URL: ${e.message}", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @PluginMethod
    fun openAuthWindow(call: PluginCall) {
        val rawUrl = call.getString("url")?.trim().orEmpty()
        val parsed = validateHttpUrl(rawUrl)
        if (parsed == null) {
            call.reject("Invalid or unsupported auth URL")
            return
        }

        val title = call.getString("title") ?: "Sign In"
        val external = call.getBoolean("external", false) ?: false

        val rawCb = call.getString("callbackPrefix")?.trim().orEmpty()
        val callbackPrefix = if (validateHttpUrl(rawCb) != null) rawCb else null

        val rawClose = call.getString("closeOnPrefix")?.trim().orEmpty()
        val closeOnPrefix = if (validateHttpUrl(rawClose) != null) rawClose else null

        val redirObj = call.getObject("redirectOnPrefix")
        val rawRedirMatch = redirObj?.getString("matchPrefix")?.trim().orEmpty()
        val rawRedirTarget = redirObj?.getString("targetUrl")?.trim().orEmpty()
        val validRedirMatch = if (validateHttpUrl(rawRedirMatch) != null) rawRedirMatch else null
        val validRedirTarget = if (validateHttpUrl(rawRedirTarget) != null) rawRedirTarget else null
        val hasRedirectRule = (validRedirMatch != null && validRedirTarget != null)
        val awaitingNav = (callbackPrefix != null || closeOnPrefix != null)

        if (external) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(parsed.toString())).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                call.resolve(JSObject().apply {
                    put("ok", true)
                    put("mode", "external")
                })
            } catch (e: Exception) {
                call.reject("Failed to open external browser: ${e.message}", e)
            }
            return
        }

        val hostActivity = activity
        if (hostActivity == null || hostActivity.isFinishing) {
            call.reject("Host activity unavailable")
            return
        }

        hostActivity.runOnUiThread {
            try {
                val existingDialog = authDialog
                val existingWebView = authWebView
                if (!awaitingNav && !hasRedirectRule &&
                    existingDialog != null && existingDialog.isShowing && existingWebView != null
                ) {
                    authTitleView?.text = title
                    existingWebView.loadUrl(parsed.toString())
                    call.resolve(JSObject().apply {
                        put("ok", true)
                        put("mode", "window")
                    })
                    return@runOnUiThread
                }

                dismissAuthDialogOnUiThread()

                if (awaitingNav) {
                    pendingAuthCall = call
                    pendingAuthMode = if (callbackPrefix != null) "callback" else "completed"
                }

                val density = hostActivity.resources.displayMetrics.density
                val root = LinearLayout(hostActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#1E1F22"))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val header = LinearLayout(hostActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#1E1F22"))
                    val padH = (16 * density).toInt()
                    val padV = (12 * density).toInt()
                    setPadding(padH, padV, padH, padV)
                }

                val titleTv = TextView(hostActivity).apply {
                    text = title
                    setTextColor(Color.parseColor("#F2F3F5"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                val closeBtn = TextView(hostActivity).apply {
                    text = "Close"
                    setTextColor(Color.parseColor("#949BA4"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    val pad = (8 * density).toInt()
                    setPadding(pad, (4 * density).toInt(), pad, (4 * density).toInt())
                    setOnClickListener {
                        dismissAuthDialogOnUiThread()
                    }
                }

                header.addView(titleTv)
                header.addView(closeBtn)

                var didRedirect = false
                var didCapture = false

                fun handleNavigationUrl(view: WebView?, urlStr: String?, isPolicyCheck: Boolean): Boolean {
                    if (urlStr.isNullOrEmpty()) return false
                    if (!didRedirect && hasRedirectRule &&
                        validRedirMatch != null && validRedirTarget != null &&
                        urlStr.startsWith(validRedirMatch)
                    ) {
                        didRedirect = true
                        view?.post { view.loadUrl(validRedirTarget) }
                        return isPolicyCheck
                    }
                    if (!didCapture && callbackPrefix != null && urlStr.startsWith(callbackPrefix)) {
                        didCapture = true
                        settlePendingAuthCall(JSObject().apply {
                            put("ok", true)
                            put("mode", "callback")
                            put("callbackUrl", urlStr)
                        })
                        dismissAuthDialogOnUiThread()
                        return true
                    }
                    if (!didCapture && closeOnPrefix != null && urlStr.startsWith(closeOnPrefix)) {
                        didCapture = true
                        settlePendingAuthCall(JSObject().apply {
                            put("ok", true)
                            put("mode", "completed")
                            put("callbackUrl", urlStr)
                        })
                        dismissAuthDialogOnUiThread()
                        return true
                    }
                    return false
                }

                val webView = WebView(hostActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                    setBackgroundColor(Color.parseColor("#1E1F22"))
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        userAgentString = userAgentString.replace("; wv", "")
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url ?: return false
                            val scheme = target.scheme?.lowercase(Locale.US)
                            return if (scheme == "http" || scheme == "https") {
                                handleNavigationUrl(view, target.toString(), true)
                            } else {
                                try {
                                    hostActivity.startActivity(Intent(Intent.ACTION_VIEW, target))
                                } catch (_: Exception) {
                                }
                                true
                            }
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            handleNavigationUrl(view, url, false)
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            handleNavigationUrl(view, url, false)
                        }
                    }
                }

                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

                root.addView(header)
                root.addView(webView)

                val dialog = Dialog(
                    hostActivity,
                    android.R.style.Theme_DeviceDefault_NoActionBar
                ).apply {
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    setContentView(root)
                    setCancelable(true)
                    setOnDismissListener {
                        cleanupWebViewOnUiThread()
                    }
                }

                authDialog = dialog
                authWebView = webView
                authTitleView = titleTv

                dialog.show()
                webView.loadUrl(parsed.toString())

                if (!awaitingNav) {
                    call.resolve(JSObject().apply {
                        put("ok", true)
                        put("mode", "window")
                    })
                }
            } catch (e: Exception) {
                if (pendingAuthCall == call) {
                    pendingAuthCall = null
                }
                call.reject("Failed to open auth window: ${e.message}", e)
            }
        }
    }

    @PluginMethod
    fun closeAuthWindow(call: PluginCall) {
        val hostActivity = activity
        if (hostActivity == null) {
            settlePendingAuthCall(JSObject().apply {
                put("ok", false)
                put("mode", pendingAuthMode)
                put("cancelled", true)
            })
            call.resolve(JSObject().apply { put("ok", true) })
            return
        }
        hostActivity.runOnUiThread {
            dismissAuthDialogOnUiThread()
            call.resolve(JSObject().apply { put("ok", true) })
        }
    }

    private fun cleanupWebViewOnUiThread() {
        val wv = authWebView
        authWebView = null
        authTitleView = null
        authDialog = null
        settlePendingAuthCall(JSObject().apply {
            put("ok", false)
            put("mode", pendingAuthMode)
            put("cancelled", true)
        })
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
        if (wv != null) {
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearCache(true)
                wv.clearFormData()
                wv.clearHistory()
                wv.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun dismissAuthDialogOnUiThread() {
        val dlg = authDialog
        if (dlg != null) {
            try {
                if (dlg.isShowing) {
                    dlg.dismiss()
                }
            } catch (_: Exception) {
            }
        }
        cleanupWebViewOnUiThread()
    }

    @PluginMethod
    fun prepareStream(call: PluginCall) {
        val rawTarget = (call.getString("url") ?: call.getString("urlOrHost")).orEmpty().trim()
        val validUrl = validateHttpUrl(rawTarget)
        if (validUrl == null) {
            call.resolve(JSObject().apply {
                put("ok", false)
                put("url", rawTarget)
            })
            return
        }

        val origin = extractExactOrigin(validUrl)
        val hdrObj = call.getObject("headers") ?: call.getObject("requestHeaders")
        val hdrMap = sanitizeHeaderMap(hdrObj)
        streamHeaderRules[origin] = hdrMap

        try {
            val port = ensureStreamServerRunning()
            val grantToken = registerRouteGrant(RouteKind.STREAM, origin, hdrMap)
            val encoded = URLEncoder.encode(validUrl.toString(), "UTF-8")
            val loopbackUrl = "http://localhost:$port/stream/$grantToken?url=$encoded"
            call.resolve(JSObject().apply {
                put("ok", true)
                put("url", loopbackUrl)
            })
            return
        } catch (_: Exception) {
        }

        call.resolve(JSObject().apply {
            put("ok", true)
            put("url", rawTarget)
        })
    }

    @PluginMethod
    fun clearStream(call: PluginCall) {
        val target = call.getString("urlOrHost")?.trim().orEmpty()
        clearOriginRulesAndGrants(streamHeaderRules, setOf(RouteKind.STREAM), target)
        call.resolve(JSObject().apply { put("ok", true) })
    }

    @PluginMethod
    fun prepareFrame(call: PluginCall) {
        val rawTarget = (call.getString("url") ?: call.getString("urlOrHost")).orEmpty().trim()
        val validUrl = validateHttpUrl(rawTarget)
        if (validUrl == null) {
            call.resolve(JSObject().apply {
                put("ok", false)
                put("url", rawTarget)
            })
            return
        }

        val origin = extractExactOrigin(validUrl)
        val isEmbedRelay = call.getBoolean("embed") ?: false
        val hdrObj = call.getObject("headers") ?: call.getObject("requestHeaders")
        val hdrMap = sanitizeHeaderMap(hdrObj)
        frameHeaderRules[origin] = hdrMap

        try {
            val port = ensureStreamServerRunning()
            val kind = if (isEmbedRelay) RouteKind.EMBED else RouteKind.FRAME
            val grantToken = registerRouteGrant(kind, origin, hdrMap)
            val encoded = URLEncoder.encode(validUrl.toString(), "UTF-8")
            val route = if (isEmbedRelay) "embed" else "frame"
            val loopbackUrl = "http://localhost:$port/$route/$grantToken?url=$encoded"
            call.resolve(JSObject().apply {
                put("ok", true)
                put("url", loopbackUrl)
                put("mode", if (isEmbedRelay) "embed-relay" else "loopback")
            })
            return
        } catch (_: Exception) {
        }

        call.resolve(JSObject().apply {
            put("ok", true)
            put("url", rawTarget)
            put("mode", "headers")
        })
    }

    @PluginMethod
    fun clearFrame(call: PluginCall) {
        val target = call.getString("urlOrHost")?.trim().orEmpty()
        clearOriginRulesAndGrants(frameHeaderRules, setOf(RouteKind.FRAME, RouteKind.EMBED), target)
        call.resolve(JSObject().apply { put("ok", true) })
    }

    private fun clearOriginRulesAndGrants(
        map: ConcurrentHashMap<String, Map<String, String>>,
        kinds: Set<RouteKind>,
        target: String
    ) {
        if (target.isEmpty()) {
            map.clear()
            routeGrants.entries.removeIf { it.value.kind in kinds }
            return
        }
        val parsed = validateHttpUrl(target)
        if (parsed != null) {
            val origin = extractExactOrigin(parsed)
            map.remove(origin)
            routeGrants.entries.removeIf { it.value.kind in kinds && it.value.allowedOrigin == origin }
            return
        }
        val hostLower = target.substringBefore('/').substringBefore(':').lowercase(Locale.US)
        if (hostLower.isEmpty() || hostLower == "*") return
        val matchedOrigins = map.keys.filter { key ->
            validateHttpUrl(key)?.host?.lowercase(Locale.US) == hostLower
        }.toSet()
        for (origin in matchedOrigins) {
            map.remove(origin)
        }
        routeGrants.entries.removeIf {
            it.value.kind in kinds &&
                (it.value.allowedOrigin in matchedOrigins ||
                    validateHttpUrl(it.value.allowedOrigin)?.host?.lowercase(Locale.US) == hostLower)
        }
    }

    private fun rewriteM3u8Playlist(
        rawText: String,
        baseUrl: URL,
        port: Int,
        sourceGrantToken: String,
        sourceOrigin: String
    ): ByteArray {
        val lines = rawText.split("\n")
        val childTokensByOrigin = mutableMapOf<String, String>()
        val rewritten = lines.joinToString("\n") { rawLine ->
            val trimmed = rawLine.trimEnd('\r')
            val s = trimmed.trim()
            if (s.isEmpty() || s.startsWith("#")) {
                trimmed
            } else {
                try {
                    val resolvedUrl = URL(baseUrl, s)
                    if (resolvedUrl.protocol != "http" && resolvedUrl.protocol != "https") {
                        trimmed
                    } else {
                        val segOrigin = extractExactOrigin(resolvedUrl)
                        val token = if (segOrigin == sourceOrigin) {
                            sourceGrantToken
                        } else {
                            childTokensByOrigin.getOrPut(segOrigin) {
                                registerRouteGrant(
                                    RouteKind.STREAM,
                                    segOrigin,
                                    streamHeaderRules[segOrigin] ?: emptyMap()
                                )
                            }
                        }
                        val enc = URLEncoder.encode(resolvedUrl.toString(), "UTF-8")
                        "http://localhost:$port/stream/$token?url=$enc"
                    }
                } catch (_: Exception) {
                    trimmed
                }
            }
        }
        return rewritten.toByteArray(Charsets.UTF_8)
    }

    private fun injectHtmlBaseHref(
        htmlBytes: ByteArray,
        effectiveUrl: String,
        frameGrantToken: String
    ): ByteArray {
        var html = htmlBytes.toString(Charsets.UTF_8)
        html = Regex(
            "<meta[^>]+http-equiv\\s*=\\s*['\"]?(?:content-security-policy|x-frame-options)['\"]?[^>]*>",
            RegexOption.IGNORE_CASE
        ).replace(html, "")
        val safeHref = effectiveUrl
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val jsBase = effectiveUrl
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "")
            .replace("</", "<\\/")
        val hasBase = Regex("<base\\s", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val baseTag = if (hasBase) "" else "<base href=\"$safeHref\">"
        val shimScript =
            "<script>(function(){" +
                "var B=\"$jsBase\",BO=new URL(B).origin,P=\"/proxy/$frameGrantToken?url=\";" +
                "function abs(u){try{return new URL(String(u),B).href;}catch(_){return String(u||'');}}" +
                "function need(u){try{var p=new URL(u,B);return(p.protocol==='http:'||p.protocol==='https:')&&p.origin===BO&&p.origin!==location.origin;}catch(_){return false;}}" +
                "try{var hp=History.prototype.pushState,hr=History.prototype.replaceState;" +
                "History.prototype.pushState=function(s,t,u){try{return hp.call(this,s,t,u);}catch(_){}};" +
                "History.prototype.replaceState=function(s,t,u){try{return hr.call(this,s,t,u);}catch(_){}};}" +
                "catch(_){}" +
                "if(window.fetch){var nf=window.fetch.bind(window);window.fetch=function(i,o){try{" +
                "var r=abs(typeof i==='string'?i:(i&&i.url?i.url:String(i)));" +
                "if(need(r)){var no=Object.assign({},o||{});" +
                "if(typeof i==='object'&&i&&!(i instanceof URL)){if(!no.method&&i.method)no.method=i.method;if(!no.headers&&i.headers)no.headers=i.headers;}" +
                "no.credentials='same-origin';return nf(P+encodeURIComponent(r),no);}" +
                "}catch(_){}return nf(i,o);};}" +
                "if(window.XMLHttpRequest){var xo=XMLHttpRequest.prototype.open;" +
                "XMLHttpRequest.prototype.open=function(m,u){var a=Array.prototype.slice.call(arguments);" +
                "try{var r=abs(u);if(need(r))a[1]=P+encodeURIComponent(r);}catch(_){}return xo.apply(this,a);};}" +
                "})();</script>"
        val injection = baseTag + shimScript
        val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        val updated = if (headMatch != null) {
            val insertPos = headMatch.range.last + 1
            html.substring(0, insertPos) + injection + html.substring(insertPos)
        } else {
            injection + html
        }
        return updated.toByteArray(Charsets.UTF_8)
    }

    private fun responseBlocksFraming(conn: HttpURLConnection): Boolean {
        for ((k, vals) in conn.headerFields) {
            if (k == null || vals == null) continue
            val keyLower = k.lowercase(Locale.US)
            if (keyLower == "x-frame-options") {
                for (raw in vals) {
                    val v = raw?.lowercase(Locale.US).orEmpty()
                    if (v.contains("deny") || v.contains("sameorigin")) {
                        return true
                    }
                }
            } else if (keyLower == "content-security-policy") {
                for (raw in vals) {
                    val policy = raw?.lowercase(Locale.US).orEmpty()
                    for (directive in policy.split(';')) {
                        val d = directive.trim()
                        if (d.startsWith("frame-ancestors")) {
                            val tokens = d.split(Regex("\\s+")).drop(1)
                            if (!tokens.contains("*") && !tokens.contains("http:")) {
                                return true
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    private fun buildEmbedRelayHtml(targetUrl: String): ByteArray {
        val safeSrc = targetUrl
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val html =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>*{margin:0;padding:0;box-sizing:border-box}html,body,iframe{width:100%;height:100%;border:0;background:#000;overflow:hidden;display:block}</style>" +
                "</head><body>" +
                "<iframe id=\"f\" src=\"$safeSrc\" referrerpolicy=\"strict-origin-when-cross-origin\" playsinline " +
                "allow=\"accelerometer; autoplay; clipboard-write; encrypted-media; fullscreen; gyroscope; picture-in-picture; web-share\" allowfullscreen></iframe>" +
                "<script>(function(){var f=document.getElementById('f'),ready=false,q=[];" +
                "f.addEventListener('load',function(){ready=true;while(q.length){try{f.contentWindow.postMessage(q.shift(),'*');}catch(_){}}});" +
                "window.addEventListener('message',function(e){try{" +
                "if(f&&e.source===f.contentWindow){window.parent.postMessage(e.data,'*');}" +
                "else if(e.source===window.parent&&f&&f.contentWindow){if(!ready&&q.length<16)q.push(e.data);f.contentWindow.postMessage(e.data,'*');}" +
                "}catch(_){}});})();</script></body></html>"
        return html.toByteArray(Charsets.UTF_8)
    }

    private fun responseHasRestrictiveFrameCookies(setCookies: List<String>): Boolean {
        for (raw in setCookies) {
            val lower = raw.lowercase(Locale.US)
            if (Regex(";\\s*samesite\\s*=\\s*(?:strict|lax)\\b").containsMatchIn(lower) ||
                !Regex(";\\s*samesite\\s*=").containsMatchIn(lower)
            ) {
                return true
            }
        }
        return false
    }

    private fun rewriteSetCookieForLoopback(rawCookie: String): String {
        val parts = rawCookie.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return rawCookie
        val out = mutableListOf(parts.first())
        var hasExpiry = false
        for (attr in parts.drop(1)) {
            val lower = attr.lowercase(Locale.US)
            when {
                lower.startsWith("domain=") -> continue
                lower.startsWith("path=") -> continue
                lower == "secure" -> continue
                lower.startsWith("samesite=") -> continue
                lower.startsWith("max-age=") || lower.startsWith("expires=") -> {
                    hasExpiry = true
                    out.add(attr)
                }
                else -> out.add(attr)
            }
        }
        out.add("Path=/")
        out.add("SameSite=Lax")
        if (!hasExpiry) {
            out.add("Max-Age=604800")
        }
        return out.joinToString("; ")
    }

    private fun mergeCookieHeader(existingCookie: String?, newSetCookies: List<String>): String? {
        val pairs = LinkedHashMap<String, String>()
        if (!existingCookie.isNullOrBlank()) {
            for (part in existingCookie.split(';')) {
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val name = part.substring(0, idx).trim()
                    val value = part.substring(idx + 1).trim()
                    if (name.isNotEmpty()) pairs[name] = value
                }
            }
        }
        for (sc in newSetCookies) {
            val first = sc.substringBefore(';').trim()
            val idx = first.indexOf('=')
            if (idx > 0) {
                val name = first.substring(0, idx).trim()
                val value = first.substring(idx + 1).trim()
                if (name.isNotEmpty()) pairs[name] = value
            }
        }
        return if (pairs.isEmpty()) null else pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun extractSetCookies(conn: HttpURLConnection): List<String> {
        val out = mutableListOf<String>()
        for ((k, vals) in conn.headerFields) {
            if (k != null && k.equals("Set-Cookie", ignoreCase = true) && vals != null) {
                for (v in vals) {
                    val clean = sanitizeHeaderValue(v)
                    if (clean.isNotEmpty()) out.add(clean)
                }
            }
        }
        return out
    }

    private data class UpstreamResult(
        val conn: HttpURLConnection,
        val finalUrl: URL,
        val setCookies: List<String>
    )

    private fun openGuardedUpstreamConnection(
        startUrl: URL,
        startMethod: String,
        initialHeaders: Map<String, String>,
        initialBody: ByteArray?,
        acceptEncoding: String,
        carrySameOriginCookies: Boolean = false
    ): UpstreamResult {
        var currentUrl = startUrl
        var currentMethod = startMethod
        var currentBody = initialBody
        val currentHeaders = initialHeaders.toMutableMap()
        val collectedSetCookies = mutableListOf<String>()

        for (hop in 0..MAX_REDIRECTS) {
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = currentMethod
                setRequestProperty("Accept-Encoding", acceptEncoding)
            }

            var hasUserAgent = false
            var hasAcceptLanguage = false
            for ((k, v) in currentHeaders) {
                if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                if (k.equals("Accept-Language", ignoreCase = true)) hasAcceptLanguage = true
                conn.setRequestProperty(k, v)
            }
            if (!hasUserAgent) {
                conn.setRequestProperty("User-Agent", DEFAULT_DESKTOP_USER_AGENT)
            }
            if (!hasAcceptLanguage) {
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }

            if (currentBody != null && currentMethod != "GET" && currentMethod != "HEAD") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(currentBody.size)
                conn.outputStream.use { reqOut ->
                    reqOut.write(currentBody)
                    reqOut.flush()
                }
            }

            val status = conn.responseCode
            val hopSetCookies = if (carrySameOriginCookies) extractSetCookies(conn) else emptyList()
            val location = conn.getHeaderField("Location")
            if (status in listOf(301, 302, 303, 307, 308) && !location.isNullOrBlank() && hop < MAX_REDIRECTS) {
                conn.disconnect()
                val nextUrl = URL(currentUrl, location)
                if (nextUrl.protocol != "http" && nextUrl.protocol != "https") {
                    throw IllegalArgumentException("Unsupported redirect protocol: ${nextUrl.protocol}")
                }
                val sameOrigin = extractExactOrigin(nextUrl) == extractExactOrigin(currentUrl)
                if (currentUrl.protocol.equals("https", ignoreCase = true) &&
                    nextUrl.protocol.equals("http", ignoreCase = true) &&
                    hasCredentialOrCustomHeaders(currentHeaders)
                ) {
                    throw SecurityException("Blocked insecure HTTPS to HTTP credentialed redirect")
                }
                if (status == 303 || ((status == 301 || status == 302) && currentMethod == "POST")) {
                    currentMethod = "GET"
                    currentBody = null
                    val iter = currentHeaders.keys.iterator()
                    while (iter.hasNext()) {
                        val hdr = iter.next()
                        if (hdr.equals("Content-Type", ignoreCase = true) ||
                            hdr.equals("Content-Length", ignoreCase = true)
                        ) {
                            iter.remove()
                        }
                    }
                }
                if (sameOrigin && carrySameOriginCookies && hopSetCookies.isNotEmpty()) {
                    collectedSetCookies.addAll(hopSetCookies)
                    val existingCookieKey = currentHeaders.keys.firstOrNull { it.equals("Cookie", ignoreCase = true) }
                    val merged = mergeCookieHeader(existingCookieKey?.let { currentHeaders[it] }, hopSetCookies)
                    if (merged != null) {
                        if (existingCookieKey != null) currentHeaders.remove(existingCookieKey)
                        currentHeaders["Cookie"] = merged
                    }
                }
                if (!sameOrigin) {
                    if ((status == 307 || status == 308) && currentBody != null) {
                        throw SecurityException("Blocked cross-origin 307/308 redirect with request body")
                    }
                    stripCrossOriginHeaders(currentHeaders)
                }
                currentUrl = nextUrl
                continue
            }

            if (carrySameOriginCookies && hopSetCookies.isNotEmpty()) {
                collectedSetCookies.addAll(hopSetCookies)
            }
            return UpstreamResult(conn, currentUrl, collectedSetCookies)
        }
        throw IllegalStateException("Too many HTTP redirects")
    }

    @Synchronized
    private fun ensureStreamServerRunning(): Int {
        val existing = streamServerSocket
        if (existing != null && !existing.isClosed && streamServerPort > 0) {
            return streamServerPort
        }

        val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val serverV6 = try {
            ServerSocket(port, 32, InetAddress.getByName("::1"))
        } catch (_: Exception) {
            null
        }
        streamServerSocket = server
        streamServerSocketV6 = serverV6
        streamServerPort = port

        ioScope.launch {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    ioScope.launch {
                        handleStreamClient(client)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        if (serverV6 != null) {
            ioScope.launch {
                while (!serverV6.isClosed) {
                    try {
                        val client = serverV6.accept()
                        ioScope.launch {
                            handleStreamClient(client)
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }

        return port
    }

    private fun handleStreamClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            val rawInput = BufferedInputStream(client.getInputStream())
            val headerBytes = ByteArrayOutputStream()
            var state = 0
            while (headerBytes.size() < 32 * 1024) {
                val b = rawInput.read()
                if (b < 0) break
                headerBytes.write(b)
                state = when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> 4
                    else -> if (b == '\r'.code) 1 else 0
                }
                if (state == 4) break
            }
            if (state != 4) return

            val headerLines = headerBytes.toString(Charsets.US_ASCII.name()).split("\r\n")
            val requestLine = headerLines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase(Locale.US)
            val rawPath = parts[1]

            val clientHeaders = mutableMapOf<String, String>()
            for (line in headerLines.drop(1)) {
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim()
                    val v = line.substring(colon + 1).trim()
                    clientHeaders[k] = v
                }
            }

            val out = client.getOutputStream()
            val reqOrigin = clientHeaders.entries
                .firstOrNull { it.key.equals("Origin", ignoreCase = true) }
                ?.value
            val corsOrigin = allowedCorsOrigin(reqOrigin)

            if (method == "OPTIONS") {
                val resp = buildString {
                    append("HTTP/1.1 204 No Content\r\n")
                    if (corsOrigin != null) {
                        append("Access-Control-Allow-Origin: ${sanitizeHeaderValue(corsOrigin)}\r\n")
                        append("Vary: Origin\r\n")
                    }
                    append("Access-Control-Allow-Methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
                    append("Access-Control-Allow-Headers: Range, If-Range, Accept, Content-Type\r\n")
                    append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type, X-Paradise-Final-Url\r\n")
                    append("Cross-Origin-Resource-Policy: cross-origin\r\n")
                    append("Referrer-Policy: no-referrer\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(resp.toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }

            val qIdx = rawPath.indexOf('?')
            val pathPart = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val queryPart = if (qIdx >= 0) rawPath.substring(qIdx + 1) else ""
            val pathSegments = pathPart.trimStart('/').split('/')
            val routeType = pathSegments.getOrNull(0).orEmpty()
            val grantToken = pathSegments.getOrNull(1).orEmpty()
            val grant = if (pathSegments.size == 2 && grantToken.isNotEmpty()) {
                routeGrants[grantToken]
            } else null

            val isFrameRoute = routeType == "frame" && grant?.kind == RouteKind.FRAME
            val isStreamRoute = routeType == "stream" && grant?.kind == RouteKind.STREAM
            val isEmbedRoute = routeType == "embed" && grant?.kind == RouteKind.EMBED
            val isProxyRoute = routeType == "proxy" && grant?.kind == RouteKind.FRAME
            val hasReqBody = method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE"
            val allowedMethod = method == "GET" || method == "HEAD" ||
                (isFrameRoute && method == "POST") ||
                (isProxyRoute && hasReqBody)

            if (grant == null || (!isStreamRoute && !isFrameRoute && !isEmbedRoute && !isProxyRoute) || !allowedMethod) {
                out.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }

            val reqContentLen = clientHeaders.entries
                .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value?.toIntOrNull()?.coerceIn(0, 8 * 1024 * 1024) ?: 0
            val reqBodyBytes: ByteArray? = if (hasReqBody && reqContentLen > 0) {
                val bodyBuf = ByteArray(reqContentLen)
                var offset = 0
                while (offset < reqContentLen) {
                    val n = rawInput.read(bodyBuf, offset, reqContentLen - offset)
                    if (n <= 0) break
                    offset += n
                }
                if (offset == reqContentLen) bodyBuf else bodyBuf.copyOf(offset)
            } else null

            val targetUrlStr = queryPart
                .split("&")
                .firstNotNullOfOrNull { pair ->
                    val eq = pair.indexOf('=')
                    if (eq > 0 && pair.substring(0, eq) == "url") {
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    } else null
                }

            val upstreamUrl = validateHttpUrl(targetUrlStr.orEmpty())
            if (upstreamUrl == null) {
                out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }

            val upstreamOrigin = extractExactOrigin(upstreamUrl)
            if (upstreamOrigin != grant.allowedOrigin) {
                out.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }

            if (isEmbedRoute) {
                val relayBytes = buildEmbedRelayHtml(upstreamUrl.toString())
                val hdrs = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${relayBytes.size}\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Cross-Origin-Resource-Policy: cross-origin\r\n")
                    append("Referrer-Policy: no-referrer\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(hdrs.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") {
                    out.write(relayBytes)
                }
                out.flush()
                return
            }

            val upstreamHeaders = mutableMapOf<String, String>()
            for (fwdHdr in listOf("Range", "If-Range", "Accept", "Content-Type")) {
                clientHeaders.entries.firstOrNull { it.key.equals(fwdHdr, ignoreCase = true) }?.let {
                    val cleanVal = sanitizeHeaderValue(it.value)
                    if (cleanVal.isNotEmpty()) {
                        upstreamHeaders[fwdHdr] = cleanVal
                    }
                }
            }
            val isInteractiveFrameReq = isFrameRoute || isProxyRoute
            if (isInteractiveFrameReq) {
                clientHeaders.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.let {
                    val cleanCookie = sanitizeHeaderValue(it.value)
                    if (cleanCookie.isNotEmpty()) {
                        upstreamHeaders["Cookie"] = cleanCookie
                    }
                }
                upstreamHeaders["Referer"] = "$upstreamOrigin/"
                upstreamHeaders["Sec-Fetch-Site"] = "same-origin"
                if (isFrameRoute) {
                    upstreamHeaders["Sec-Fetch-Dest"] = "document"
                    upstreamHeaders["Sec-Fetch-Mode"] = "navigate"
                } else {
                    upstreamHeaders["Origin"] = upstreamOrigin
                    upstreamHeaders["Sec-Fetch-Dest"] = "empty"
                    upstreamHeaders["Sec-Fetch-Mode"] = "cors"
                }
            }
            for ((k, v) in grant.headers) {
                upstreamHeaders[k] = v
            }

            val upstreamRes = try {
                openGuardedUpstreamConnection(
                    startUrl = upstreamUrl,
                    startMethod = method,
                    initialHeaders = upstreamHeaders,
                    initialBody = reqBodyBytes,
                    acceptEncoding = if (isStreamRoute) "identity" else "gzip",
                    carrySameOriginCookies = isInteractiveFrameReq
                )
            } catch (_: Exception) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }
            val conn = upstreamRes.conn
            val finalUrl = upstreamRes.finalUrl
            val collectedSetCookies = upstreamRes.setCookies

            try {
                val status = conn.responseCode
                val msg = sanitizeHeaderValue(conn.responseMessage ?: "OK")
                val finalUrlStr = sanitizeHeaderValue(finalUrl.toString())
                val contentType = (conn.contentType ?: "").lowercase(Locale.US)
                val isGzip = "gzip".equals(conn.contentEncoding, ignoreCase = true)
                val isM3u8 = isStreamRoute && (
                    contentType.contains("mpegurl") ||
                        finalUrl.path.lowercase(Locale.US).endsWith(".m3u8")
                    )
                val isHtmlFrame = isFrameRoute && contentType.contains("text/html")

                val bufferedRewrite: ByteArray? = if (method != "HEAD" && (isM3u8 || isHtmlFrame || isGzip)) {
                    val rawS = if (status >= 400) conn.errorStream else conn.inputStream
                    val decoded = if (rawS == null) {
                        ByteArray(0)
                    } else {
                        val s = if (isGzip) GZIPInputStream(rawS) else BufferedInputStream(rawS)
                        s.use { readAllBytesCompat(it) }
                    }
                    when {
                        isM3u8 -> rewriteM3u8Playlist(
                            decoded.toString(Charsets.UTF_8),
                            finalUrl,
                            streamServerPort,
                            grantToken,
                            grant.allowedOrigin
                        )
                        isHtmlFrame -> {
                            if (method == "GET" &&
                                status in 200..299 &&
                                !responseBlocksFraming(conn) &&
                                !responseHasRestrictiveFrameCookies(collectedSetCookies)
                            ) {
                                buildEmbedRelayHtml(finalUrlStr)
                            } else {
                                injectHtmlBaseHref(decoded, finalUrlStr, grantToken)
                            }
                        }
                        else -> decoded
                    }
                } else null

                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 $status $msg\r\n")

                val passHeaders = setOf(
                    "content-type",
                    "content-length",
                    "content-range",
                    "accept-ranges",
                    "etag",
                    "last-modified",
                    "cache-control"
                )
                for ((k, vals) in conn.headerFields) {
                    if (k == null || vals == null || vals.isEmpty()) continue
                    val lower = k.lowercase(Locale.US)
                    if (bufferedRewrite != null && lower == "content-length") continue
                    if (lower in passHeaders) {
                        val cleanK = sanitizeHeaderValue(k)
                        val cleanV = sanitizeHeaderValue(vals.first())
                        if (cleanK.isNotEmpty()) {
                            headerBuilder.append("$cleanK: $cleanV\r\n")
                        }
                    }
                }
                if (isInteractiveFrameReq && collectedSetCookies.isNotEmpty()) {
                    for (rawCookie in collectedSetCookies) {
                        val rewritten = sanitizeHeaderValue(rewriteSetCookieForLoopback(rawCookie))
                        if (rewritten.isNotEmpty()) {
                            headerBuilder.append("Set-Cookie: $rewritten\r\n")
                        }
                    }
                }
                if (bufferedRewrite != null) {
                    headerBuilder.append("Content-Length: ${bufferedRewrite.size}\r\n")
                }

                headerBuilder.append("X-Paradise-Final-Url: $finalUrlStr\r\n")
                if (corsOrigin != null) {
                    headerBuilder.append("Access-Control-Allow-Origin: ${sanitizeHeaderValue(corsOrigin)}\r\n")
                    headerBuilder.append("Vary: Origin\r\n")
                }
                headerBuilder.append("Access-Control-Allow-Methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
                headerBuilder.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type, X-Paradise-Final-Url\r\n")
                headerBuilder.append("Cross-Origin-Resource-Policy: cross-origin\r\n")
                headerBuilder.append("Referrer-Policy: no-referrer\r\n")
                headerBuilder.append("Connection: close\r\n\r\n")
                out.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))

                if (method != "HEAD") {
                    if (bufferedRewrite != null) {
                        out.write(bufferedRewrite)
                    } else {
                        val stream = if (status >= 400) conn.errorStream else conn.inputStream
                        stream?.use { input ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                out.flush()
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun validateHttpUrl(raw: String): URL? {
        if (raw.isBlank() || raw.trim() == "*") return null
        return try {
            val u = URL(raw)
            if ((u.protocol == "http" || u.protocol == "https") && u.host.isNotBlank()) u else null
        } catch (_: Exception) {
            null
        }
    }

    override fun handleOnDestroy() {
        activity?.runOnUiThread {
            dismissAuthDialogOnUiThread()
        }
        try {
            streamServerSocket?.close()
        } catch (_: Exception) {
        }
        try {
            streamServerSocketV6?.close()
        } catch (_: Exception) {
        }
        streamServerSocket = null
        streamServerSocketV6 = null
        streamServerPort = 0
        ioScope.cancel()
        super.handleOnDestroy()
    }
}
