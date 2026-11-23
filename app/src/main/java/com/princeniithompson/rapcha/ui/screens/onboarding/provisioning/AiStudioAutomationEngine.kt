package com.princeniithompson.rapcha.ui.screens.onboarding.provisioning

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.PersistableBundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.princeniithompson.rapcha.core.ApiKeyFormat
import com.princeniithompson.rapcha.core.ApiKeyVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProvisioningStatus {
    IDLE,
    SIGNING_IN,
    SIGNED_IN_HANDOFF,
    PROVISIONING_BACKGROUND,
    NEEDS_AUTH_RECONFIRM,
    KEY_READY,
    FAILED
}

data class ProvisioningUiState(
    val status: ProvisioningStatus = ProvisioningStatus.IDLE,
    val currentUrl: String = "",
    val pageTitle: String = "Sign in - Google Accounts",
    val loadProgress: Int = 0,
    val isPageLoading: Boolean = false,
    val detectedEmail: String = "",
    val showBrowserToUser: Boolean = false,
    val keyProvisioned: Boolean = false,
    val statusDetail: String = ""
)

/**
 * Orchestrates embedded Google Sign-In + silent background Gemini API key harvesting inside a
 * full-viewport WebView using local DOM/ShadowDOM math and real MotionEvent taps (zero AI).
 */
class AiStudioAutomationEngine(private val context: Context) {

    private val appContext = context.applicationContext ?: context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ProvisioningUiState())
    val state: StateFlow<ProvisioningUiState> = _state.asStateFlow()

    private val _activeWebView = MutableStateFlow<WebView?>(null)
    val activeWebView: StateFlow<WebView?> = _activeWebView.asStateFlow()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var controller: FindInPageController? = null

    @Volatile
    private var automationJob: Job? = null

    @Volatile
    private var startupRefreshCountDone = 0

    @Volatile
    private var getApiKeyTapCount = 0

    @Volatile
    private var routeGuardLoadFallbackCount = 0

    @Volatile
    private var hasEnteredHandoffForCurrentSignIn = false

    @Volatile
    private var isHostForeground = true

    @Volatile
    private var pendingRecoveryUrl: String? = null

    @Volatile
    private var rendererRecoveryCount = 0

    @Volatile
    private var lastLoadedUrl = ""

    @Volatile
    private var clipListenerRegistered = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (_state.value.keyProvisioned) return@OnPrimaryClipChangedListener
        scope.launch {
            val found = readKeyFromClipboard()
            if (found != null && !_state.value.keyProvisioned) {
                onValidKeyCaptured(found, fromClipboard = true)
            }
        }
    }

    private inner class RapchaKeyBridge {
        @JavascriptInterface
        fun onKeyCopied(rawText: String?) {
            val extracted = ApiKeyFormat.extractFirstGeminiKey(rawText) ?: return
            if (_state.value.keyProvisioned) return
            scope.launch {
                if (!_state.value.keyProvisioned) {
                    onValidKeyCaptured(extracted, fromClipboard = false)
                }
            }
        }
    }

    companion object {
        fun shouldAllowHiddenBrowser(
            hasVerifiedWorkingKey: Boolean,
            onboardingCompleted: Boolean,
            forceRecovery: Boolean = false
        ): Boolean = forceRecovery || !hasVerifiedWorkingKey || !onboardingCompleted
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun createOrReuseWebView(forceRecovery: Boolean = false): WebView {
        webView?.let { return it }

        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            setLayerType(View.LAYER_TYPE_NONE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val priority = if (isHostForeground) {
                    WebView.RENDERER_PRIORITY_IMPORTANT
                } else {
                    WebView.RENDERER_PRIORITY_WAIVED
                }
                setRendererPriorityPolicy(priority, !isHostForeground)
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                val defaultUa = WebSettings.getDefaultUserAgent(context)
                userAgentString = ProvisioningWebMath.stripWebViewTokenFromUserAgent(defaultUa)
            }

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(RapchaKeyBridge(), "RapchaKeyBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    _state.value = _state.value.copy(
                        loadProgress = newProgress,
                        isPageLoading = newProgress < 100
                    )
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val clean = title?.trim().orEmpty()
                    if (clean.isNotEmpty() && !clean.startsWith("http")) {
                        _state.value = _state.value.copy(pageTitle = clean)
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    if (url.isEmpty()) return false
                    if (url.startsWith("intent:", ignoreCase = true)) {
                        val fallback = ProvisioningWebMath.extractIntentFallbackUrl(url)
                        if (!fallback.isNullOrEmpty()) {
                            view?.loadUrl(fallback)
                        }
                        return true
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    val u = url.orEmpty()
                    if (u.startsWith("http")) lastLoadedUrl = u
                    _state.value = _state.value.copy(
                        currentUrl = u,
                        isPageLoading = true
                    )
                    if (ProvisioningWebMath.isAiStudioUrl(u)) {
                        injectClipboardCopyCapture(view)
                    }
                    handleUrlTransition(u)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val u = url.orEmpty()
                    if (u.startsWith("http")) lastLoadedUrl = u
                    _state.value = _state.value.copy(
                        currentUrl = u,
                        isPageLoading = false,
                        loadProgress = 100
                    )
                    if (ProvisioningWebMath.isGoogleAuthUrl(u)) {
                        injectGoogleAccountTapCapture(view)
                    } else if (ProvisioningWebMath.isAiStudioUrl(u)) {
                        injectClipboardCopyCapture(view)
                    }
                    handleUrlTransition(u)
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    if (view != null && view != webView) return true
                    val urlToRecover = lastLoadedUrl.ifBlank {
                        if (hasEnteredHandoffForCurrentSignIn) {
                            ProvisioningWebMath.AI_STUDIO_API_KEYS_URL
                        } else {
                            ProvisioningWebMath.buildGoogleSignInUrl()
                        }
                    }
                    tearDownWebView()
                    if (!_state.value.keyProvisioned) {
                        if (rendererRecoveryCount < 2) {
                            rendererRecoveryCount++
                            if (isHostForeground) {
                                scope.launch {
                                    delay(250L)
                                    recoverAfterRendererLoss(urlToRecover)
                                }
                            } else {
                                pendingRecoveryUrl = urlToRecover
                            }
                        } else {
                            _state.value = _state.value.copy(
                                status = ProvisioningStatus.FAILED,
                                showBrowserToUser = false,
                                statusDetail = "Browser session closed unexpectedly"
                            )
                        }
                    }
                    return true
                }
            }
        }

        webView = wv
        controller = FindInPageController(wv)
        _activeWebView.value = wv
        registerClipboardListenerIfNeeded()
        return wv
    }

    private fun registerClipboardListenerIfNeeded() {
        if (clipListenerRegistered) return
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.addPrimaryClipChangedListener(clipboardListener)
            clipListenerRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterClipboardListener() {
        if (!clipListenerRegistered) return
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.removePrimaryClipChangedListener(clipboardListener)
        } catch (_: Throwable) {
        } finally {
            clipListenerRegistered = false
        }
    }

    fun onHostPaused() {
        isHostForeground = false
        webView?.let { wv ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
                }
                wv.onPause()
            } catch (_: Throwable) {
            }
        }
    }

    fun onHostResumed() {
        isHostForeground = true
        val onboardingDone = com.princeniithompson.rapcha.data.OnboardingRepository.isOnboardingCompleted.value
        if (!shouldAllowHiddenBrowser(ApiKeyVault.hasVerifiedWorkingKey(), onboardingDone)) {
            if (!_state.value.showBrowserToUser) {
                tearDownWebView()
            }
            return
        }
        if (!_state.value.keyProvisioned) {
            scope.launch {
                val clipKey = readKeyFromClipboard()
                if (clipKey != null && !_state.value.keyProvisioned) {
                    onValidKeyCaptured(clipKey, fromClipboard = true)
                }
            }
        }
        val recoverUrl = pendingRecoveryUrl
        if (recoverUrl != null && !_state.value.keyProvisioned) {
            pendingRecoveryUrl = null
            recoverAfterRendererLoss(recoverUrl)
            return
        }
        val existingWv = webView
        if (existingWv != null) {
            if (!_state.value.keyProvisioned) {
                registerClipboardListenerIfNeeded()
            }
            try {
                existingWv.onResume()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    existingWv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                }
            } catch (_: Throwable) {
            }
            if (!_state.value.keyProvisioned &&
                (_state.value.status == ProvisioningStatus.PROVISIONING_BACKGROUND ||
                    _state.value.status == ProvisioningStatus.SIGNED_IN_HANDOFF ||
                    _state.value.status == ProvisioningStatus.FAILED)
            ) {
                if (_state.value.status == ProvisioningStatus.FAILED) {
                    _state.value = _state.value.copy(
                        status = ProvisioningStatus.PROVISIONING_BACKGROUND,
                        statusDetail = "Resuming key setup"
                    )
                }
                ensureBackgroundAutomationRunning()
            }
        } else if (!_state.value.keyProvisioned) {
            resumeSilentProvisioningIfPossible()
        }
    }

    /**
     * Automatically resumes silent key harvesting after a low-RAM process restart or 2FA app switch
     * if Google sign-in was already started or cookies exist in CookieManager.
     */
    fun resumeSilentProvisioningIfPossible(): Boolean {
        if (_state.value.keyProvisioned || ApiKeyVault.hasVerifiedWorkingKey()) return false
        val signInStarted = com.princeniithompson.rapcha.data.OnboardingRepository.isGoogleSignInStarted()
        val hasSavedEmail = ApiKeyVault.googleEmailFlow.value.isNotBlank()
        val hasCookies = try {
            val cm = CookieManager.getInstance()
            cm.hasCookies() && (
                !cm.getCookie("https://aistudio.google.com").isNullOrBlank() ||
                    !cm.getCookie("https://accounts.google.com").isNullOrBlank()
                )
        } catch (_: Throwable) {
            false
        }
        if (!signInStarted && !hasSavedEmail && !hasCookies) return false

        val wv = createOrReuseWebView(forceRecovery = true)
        if (_state.value.status == ProvisioningStatus.IDLE || _state.value.status == ProvisioningStatus.FAILED) {
            _state.value = _state.value.copy(
                status = ProvisioningStatus.PROVISIONING_BACKGROUND,
                showBrowserToUser = false,
                statusDetail = "Finishing key setup"
            )
            if (wv.url.isNullOrBlank() || wv.url == "about:blank") {
                wv.loadUrl(ProvisioningWebMath.AI_STUDIO_API_KEYS_URL)
            }
        }
        ensureBackgroundAutomationRunning()
        return true
    }

    private fun recoverAfterRendererLoss(urlToLoad: String) {
        if (_state.value.keyProvisioned) return
        val onboardingDone = com.princeniithompson.rapcha.data.OnboardingRepository.isOnboardingCompleted.value
        if (!shouldAllowHiddenBrowser(ApiKeyVault.hasVerifiedWorkingKey(), onboardingDone)) return
        val wv = createOrReuseWebView()
        startupRefreshCountDone = 0
        wv.loadUrl(urlToLoad)
        if (_state.value.status == ProvisioningStatus.PROVISIONING_BACKGROUND ||
            _state.value.status == ProvisioningStatus.SIGNED_IN_HANDOFF
        ) {
            ensureBackgroundAutomationRunning()
        }
    }

    fun startGoogleSignIn(emailHint: String? = null) {
        com.princeniithompson.rapcha.data.OnboardingRepository.setGoogleSignInStarted(true)
        val wv = createOrReuseWebView(forceRecovery = true)
        startupRefreshCountDone = 0
        getApiKeyTapCount = 0
        routeGuardLoadFallbackCount = 0
        hasEnteredHandoffForCurrentSignIn = false

        val signInUrl = ProvisioningWebMath.buildGoogleSignInUrl(emailHint)
        _state.value = _state.value.copy(
            status = ProvisioningStatus.SIGNING_IN,
            showBrowserToUser = true,
            currentUrl = signInUrl,
            pageTitle = "Sign in - Google Accounts",
            keyProvisioned = false,
            statusDetail = "Sign in with Google"
        )
        wv.loadUrl(signInUrl)
    }

    fun switchGoogleAccount() {
        automationJob?.cancel()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            cm.flush()
            scope.launch {
                ApiKeyVault.clearUserCredentials(appContext)
                startGoogleSignIn()
            }
        }
    }

    fun dismissVisibleBrowser() {
        if (_state.value.status == ProvisioningStatus.SIGNING_IN) {
            _state.value = _state.value.copy(
                status = ProvisioningStatus.IDLE,
                showBrowserToUser = false
            )
            webView?.stopLoading()
        } else {
            _state.value = _state.value.copy(showBrowserToUser = false)
        }
    }

    fun reloadCurrentPage() {
        webView?.reload()
    }

    fun openDiagnosticsBrowser() {
        _state.value = _state.value.copy(showBrowserToUser = true)
    }

    fun hideDiagnosticsBrowser() {
        if (_state.value.status != ProvisioningStatus.SIGNING_IN &&
            _state.value.status != ProvisioningStatus.NEEDS_AUTH_RECONFIRM
        ) {
            _state.value = _state.value.copy(showBrowserToUser = false)
        }
    }

    /**
     * Called when the user taps "Open Rapcha" on the post-login handoff screen.
     * Drops the browser strictly behind the onboarding UI (alpha = 0f) and starts/continues
     * silent background provisioning.
     */
    fun onUserTappedOpenRapcha() {
        _state.value = _state.value.copy(
            status = if (_state.value.keyProvisioned) {
                ProvisioningStatus.KEY_READY
            } else {
                ProvisioningStatus.PROVISIONING_BACKGROUND
            },
            showBrowserToUser = false
        )
        if (!_state.value.keyProvisioned) {
            ensureBackgroundAutomationRunning()
        }
    }

    private fun handleUrlTransition(url: String) {
        if (_state.value.keyProvisioned) return

        if (ProvisioningWebMath.isGoogleAuthUrl(url)) {
            if (_state.value.status == ProvisioningStatus.PROVISIONING_BACKGROUND) {
                _state.value = _state.value.copy(
                    status = ProvisioningStatus.NEEDS_AUTH_RECONFIRM,
                    showBrowserToUser = true,
                    statusDetail = "Confirm Google sign-in"
                )
            }
            return
        }

        if (ProvisioningWebMath.isAiStudioUrl(url)) {
            // Capture any visible account email right away for the "Log in as <email>" handoff
            scope.launch {
                captureAccountEmailIfAvailable()
            }

            if (_state.value.status == ProvisioningStatus.SIGNING_IN && !hasEnteredHandoffForCurrentSignIn) {
                hasEnteredHandoffForCurrentSignIn = true
                _state.value = _state.value.copy(
                    status = ProvisioningStatus.SIGNED_IN_HANDOFF,
                    showBrowserToUser = false,
                    statusDetail = "Google account connected"
                )
                // Start the silent background key harvester immediately while user sees "Open Rapcha"
                ensureBackgroundAutomationRunning()
            } else if (_state.value.status == ProvisioningStatus.NEEDS_AUTH_RECONFIRM) {
                _state.value = _state.value.copy(
                    status = ProvisioningStatus.PROVISIONING_BACKGROUND,
                    showBrowserToUser = false,
                    statusDetail = "Finishing setup"
                )
                ensureBackgroundAutomationRunning()
            }
        }
    }

    private fun injectGoogleAccountTapCapture(view: WebView?) {
        val script = """
            (function() {
                if (window.__rapchaEmailHooked) return;
                window.__rapchaEmailHooked = true;
                var emailRe = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;
                document.addEventListener('click', function(ev) {
                    var el = ev.target;
                    while (el && el !== document.body) {
                        var attr = (el.getAttribute && (el.getAttribute('data-identifier') || el.getAttribute('data-email'))) || '';
                        if (attr && emailRe.test(attr)) {
                            window.__rapchaTappedEmail = attr.match(emailRe)[0];
                            return;
                        }
                        var txt = el.innerText || '';
                        var m = txt.match(emailRe);
                        if (m && m[0]) {
                            window.__rapchaTappedEmail = m[0];
                            return;
                        }
                        el = el.parentElement;
                    }
                }, true);
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun injectClipboardCopyCapture(view: WebView?) {
        val script = """
            (function() {
                if (window.__rapchaCopyHooked) return;
                window.__rapchaCopyHooked = true;
                var keyRe = /(AIzaSy[A-Za-z0-9_-]{28,}|AQ\.[A-Za-z0-9_.-]{28,})/;
                function extractKey(val) {
                    if (!val) return '';
                    var m = String(val).match(keyRe);
                    return m ? m[0] : '';
                }
                function maskWithDots(str) {
                    var n = Math.min(Math.max(String(str || '').length, 24), 40);
                    return new Array(n + 1).join('\u2022');
                }
                function report(val) {
                    try {
                        if (val && window.RapchaKeyBridge && window.RapchaKeyBridge.onKeyCopied) {
                            window.RapchaKeyBridge.onKeyCopied(String(val));
                        }
                    } catch (e) {}
                }
                try {
                    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
                        var origWrite = navigator.clipboard.writeText.bind(navigator.clipboard);
                        navigator.clipboard.writeText = function(text) {
                            var found = extractKey(text);
                            if (found) {
                                report(found);
                                return Promise.resolve();
                            }
                            return origWrite(text);
                        };
                    }
                } catch (e2) {}
                try {
                    if (typeof document.execCommand === 'function') {
                        var origExec = document.execCommand.bind(document);
                        document.execCommand = function(cmd) {
                            if (String(cmd || '').toLowerCase() === 'copy') {
                                var sel = window.getSelection ? window.getSelection().toString() : '';
                                var activeVal = (document.activeElement && typeof document.activeElement.value === 'string')
                                    ? document.activeElement.value : '';
                                var found = extractKey(sel) || extractKey(activeVal);
                                if (found) {
                                    report(found);
                                    return true;
                                }
                            }
                            return origExec.apply(document, arguments);
                        };
                    }
                } catch (e3) {}
                try {
                    document.addEventListener('copy', function(ev) {
                        try {
                            var cd = (ev && ev.clipboardData && ev.clipboardData.getData)
                                ? ev.clipboardData.getData('text/plain') : '';
                            var sel = window.getSelection ? window.getSelection().toString() : '';
                            var activeVal = (document.activeElement && typeof document.activeElement.value === 'string')
                                ? document.activeElement.value : '';
                            var found = extractKey(cd) || extractKey(sel) || extractKey(activeVal);
                            if (found) {
                                report(found);
                                if (ev && ev.preventDefault) ev.preventDefault();
                                if (ev && ev.stopImmediatePropagation) ev.stopImmediatePropagation();
                                if (ev && ev.clipboardData && ev.clipboardData.setData) {
                                    ev.clipboardData.setData('text/plain', maskWithDots(found));
                                }
                            }
                        } catch (e4) {}
                    }, true);
                } catch (e5) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private suspend fun captureAccountEmailIfAvailable() {
        val ctrl = controller ?: return
        val tappedRaw = ctrl.evaluateJavascript("(function(){ return window.__rapchaTappedEmail || ''; })();")
            .trim()
            .removeSurrounding("\"")
        val found = ApiKeyFormat.extractFirstEmail(tappedRaw)
            ?: ctrl.extractSignedInEmailFromDom()
        if (!found.isNullOrEmpty() && found != _state.value.detectedEmail) {
            _state.value = _state.value.copy(detectedEmail = found)
            ApiKeyVault.saveGoogleEmail(appContext, found)
        }
        captureGoogleProfileFromDom(ctrl, found)
    }

    private suspend fun captureGoogleProfileFromDom(ctrl: FindInPageController, fallbackEmail: String?) {
        val js = """
            (function() {
                try {
                    var name = '';
                    var email = '';
                    var avatar = '';
                    var emailRe = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;
                    var btns = document.querySelectorAll('a[aria-label*="Google Account"], button[aria-label*="Google Account"], [aria-label*="@"]');
                    for (var i = 0; i < btns.length; i++) {
                        var label = btns[i].getAttribute('aria-label') || '';
                        var em = label.match(emailRe);
                        if (em && !email) email = em[0];
                        if (label.indexOf('Google Account:') !== -1 && !name) {
                            var after = label.split('Google Account:')[1] || '';
                            after = after.replace(emailRe, '').replace(/[()]/g, '').replace(/\s+/g, ' ').trim();
                            if (after.length >= 2 && after.length <= 64) name = after;
                        }
                        var img = btns[i].querySelector('img[src*="googleusercontent.com"], img[src*="ggpht.com"]');
                        if (img && img.src && !avatar) avatar = img.src;
                    }
                    if (!avatar) {
                        var imgs = document.querySelectorAll('img[src*="lh3.googleusercontent.com"], img[src*="googleusercontent.com/a/"]');
                        for (var j = 0; j < imgs.length; j++) {
                            var s = imgs[j].src || '';
                            if (s.indexOf('https://') === 0) { avatar = s; break; }
                        }
                    }
                    return JSON.stringify({ name: name, email: email, avatar: avatar });
                } catch (e) {
                    return '';
                }
            })();
        """.trimIndent()
        val raw = ctrl.evaluateJavascript(js)
        val clean = try {
            org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (clean.isBlank()) return
        try {
            val obj = org.json.JSONObject(clean)
            val name = obj.optString("name", "").trim()
            val email = obj.optString("email", "").trim().ifBlank { fallbackEmail.orEmpty() }
            val avatar = obj.optString("avatar", "").trim()
            if (email.isNotBlank() && email != _state.value.detectedEmail) {
                _state.value = _state.value.copy(detectedEmail = email)
                ApiKeyVault.saveGoogleEmail(appContext, email)
            }
            if (name.isNotBlank() || email.isNotBlank() || avatar.isNotBlank()) {
                com.princeniithompson.rapcha.data.UserProfileRepository.saveProfileFromWeb(
                    context = appContext,
                    displayName = name,
                    email = email,
                    avatarUrl = avatar
                )
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Quietly loads Google AI Studio's Rate Limits page in the background WebView using the user's
     * existing Google session cookies, captures their real Google profile photo & display name, and
     * parses ONLY Rapcha's metered models (`Gemini 3.5 Flash Lite` & `Gemini 3.5 Transcribe`) plus
     * account tier (`Free tier` vs paid tier). Never pops up a browser window.
     */
    fun syncProfileAndRateLimitsSilently(onComplete: (String) -> Unit = {}) {
        if (com.princeniithompson.rapcha.data.UserProfileRepository.profile.value.isSyncing) return
        val hasCookies = try {
            val cm = CookieManager.getInstance()
            cm.hasCookies() && (
                !cm.getCookie("https://aistudio.google.com").isNullOrBlank() ||
                    !cm.getCookie("https://accounts.google.com").isNullOrBlank()
                )
        } catch (_: Throwable) {
            false
        }
        if (!hasCookies) {
            onComplete("Sign in with Google once to sync your photo and tier limits")
            return
        }

        com.princeniithompson.rapcha.data.UserProfileRepository.setSyncing(true, "Syncing profile & limits...")
        scope.launch {
            var resultMsg = "Limits refreshed"
            try {
                val wv = withContext(Dispatchers.Main) {
                    val created = createOrReuseWebView(forceRecovery = false)
                    created.loadUrl("https://aistudio.google.com/rate-limit")
                    created
                }
                val ctrl = controller
                if (ctrl == null) {
                    resultMsg = "Could not start sync"
                    return@launch
                }

                waitUntilLoaded(wv)
                var synced = false
                for (attempt in 1..14) {
                    if (!scope.isActive) break
                    val url = withContext(Dispatchers.Main) { wv.url.orEmpty() }
                    if (ProvisioningWebMath.isGoogleAuthUrl(url)) {
                        resultMsg = "Reconnect via Google in Account to sync photo & limits"
                        break
                    }
                    captureGoogleProfileFromDom(ctrl, ApiKeyVault.googleEmailFlow.value)
                    val bodyRaw = ctrl.evaluateJavascript("(function(){ return (document.body && document.body.innerText) ? document.body.innerText : ''; })();")
                    val bodyText = try {
                        org.json.JSONTokener(bodyRaw).nextValue()?.toString().orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    val parsed = com.princeniithompson.rapcha.data.ApiUsageRepository.parseAiStudioRateLimitText(bodyText)
                    if (parsed != null && (parsed.modulesLimit != null || parsed.offlineLimit != null)) {
                        com.princeniithompson.rapcha.data.ApiUsageRepository.applySyncedRateLimits(parsed)
                        resultMsg = "Synced (${parsed.tierLabel})"
                        synced = true
                        break
                    }
                    delay(850L)
                }
                if (!synced && resultMsg == "Limits refreshed") {
                    resultMsg = "Profile checked; using current daily limits"
                }
            } catch (_: Throwable) {
                resultMsg = "Sync paused — using local daily counter"
            } finally {
                com.princeniithompson.rapcha.data.UserProfileRepository.setSyncing(false, resultMsg)
                if (_state.value.keyProvisioned || ApiKeyVault.hasVerifiedWorkingKey()) {
                    tearDownWebView()
                }
                withContext(Dispatchers.Main) {
                    onComplete(resultMsg)
                }
            }
        }
    }

    fun ensureBackgroundAutomationRunning() {
        if (_state.value.keyProvisioned) return
        if (automationJob?.isActive == true) return
        registerClipboardListenerIfNeeded()

        automationJob = scope.launch {
            try {
                runAutomationLoop()
            } catch (_: CancellationException) {
                // Clean cancellation
            } catch (_: Throwable) {
                if (!_state.value.keyProvisioned) {
                    _state.value = _state.value.copy(status = ProvisioningStatus.FAILED)
                }
            }
        }
    }

    private suspend fun runAutomationLoop() {
        val wv = webView ?: return
        val ctrl = controller ?: return

        waitUntilLoaded(wv)
        runTwoRefreshWarmupIfNeeded(wv)

        for (cycle in 1..16) {
            if (!scope.isActive || _state.value.keyProvisioned) return

            while (scope.isActive && !isHostForeground && !_state.value.keyProvisioned) {
                delay(350L)
            }
            if (!scope.isActive || _state.value.keyProvisioned) return
            val activeWv = webView ?: return
            val activeCtrl = controller ?: return

            waitUntilLoaded(activeWv)
            val currentUrl = withContext(Dispatchers.Main) { activeWv.url.orEmpty() }

            // Auth gate: pause silently and wait while WebView surfaces for user verification
            if (ProvisioningWebMath.isGoogleAuthUrl(currentUrl)) {
                _state.value = _state.value.copy(
                    status = ProvisioningStatus.NEEDS_AUTH_RECONFIRM,
                    showBrowserToUser = true
                )
                waitUntilOutOfAuthUrl(activeWv)
                if (_state.value.keyProvisioned) return
                _state.value = _state.value.copy(
                    status = ProvisioningStatus.PROVISIONING_BACKGROUND,
                    showBrowserToUser = false
                )
                waitUntilLoaded(activeWv)
            }

            waitForPageFullyLoaded(activeWv, activeCtrl)
            captureAccountEmailIfAvailable()

            if (checkKeyNow(activeCtrl)) return

            // Bridge A — Welcome / Terms checkboxes & Continue
            if (runBridgeACheckboxesAndContinue(activeCtrl)) {
                delay(700L)
                if (checkKeyNow(activeCtrl)) return
                continue
            }

            // Bridge B — Settled route guard (anti-loop)
            if (runBridgeBSettledRouteGuard(activeWv, activeCtrl)) {
                delay(700L)
                if (checkKeyNow(activeCtrl)) return
                continue
            }

            // Bridge C — Existing 'Rapcha 30frst' key row or API key details modal
            if (runBridgeCExistingKeyCopy(activeCtrl)) {
                return
            }

            // Bridge D — Create-Key / Create-Project Modal Completion
            if (runBridgeDCreateKeyModal(activeCtrl)) {
                delay(1_100L)
                if (checkKeyNow(activeCtrl)) return
                continue
            }

            // On cycle 1, let async key table rows finish rendering before creating a new key
            if (cycle == 1) {
                delay(1_000L)
                if (runBridgeCExistingKeyCopy(activeCtrl)) {
                    return
                }
            }

            // Bridge E — No 'Rapcha 30frst' key found ("create api key")
            if (runBridgeECreateApiKeyButton(activeCtrl)) {
                delay(900L)
                if (checkKeyNow(activeCtrl)) return
                continue
            }

            // Viewport scroll fallback during initial cycles
            if (cycle <= 2) {
                activeCtrl.dispatchVerticalScrollSwipeDown()
                delay(550L)
            } else {
                delay(800L)
            }
        }

        if (!_state.value.keyProvisioned && _state.value.status == ProvisioningStatus.PROVISIONING_BACKGROUND) {
            _state.value = _state.value.copy(
                status = ProvisioningStatus.FAILED,
                statusDetail = "Could not finish automatic key setup"
            )
        }
    }

    private suspend fun waitUntilLoaded(wv: WebView) {
        for (i in 0 until 60) {
            val ready = withContext(Dispatchers.Main) {
                !_state.value.isPageLoading && wv.progress >= 100
            }
            if (ready) return
            delay(250L)
        }
    }

    private suspend fun waitUntilOutOfAuthUrl(wv: WebView) {
        while (scope.isActive) {
            val url = withContext(Dispatchers.Main) { wv.url.orEmpty() }
            if (ProvisioningWebMath.isAiStudioUrl(url)) return
            delay(400L)
        }
    }

    private suspend fun runTwoRefreshWarmupIfNeeded(wv: WebView) {
        if (startupRefreshCountDone > 0) return
        val url = withContext(Dispatchers.Main) { wv.url.orEmpty() }
        if (ProvisioningWebMath.isGoogleAuthUrl(url)) return

        withContext(Dispatchers.Main) { wv.reload() }
        delay(200L)
        waitUntilLoaded(wv)

        delay(2_500L)

        val midUrl = withContext(Dispatchers.Main) { wv.url.orEmpty() }
        if (!ProvisioningWebMath.isGoogleAuthUrl(midUrl)) {
            withContext(Dispatchers.Main) { wv.reload() }
            delay(200L)
            waitUntilLoaded(wv)
        }
        startupRefreshCountDone = 2
    }

    private suspend fun waitForPageFullyLoaded(wv: WebView, ctrl: FindInPageController) {
        var consecutiveReady = 0
        var lastReadyUrl = ""
        for (attempt in 0 until 24) {
            val probe = ctrl.probePageReadiness()
            if (probe.ready && probe.url.isNotEmpty()) {
                if (probe.url == lastReadyUrl) {
                    consecutiveReady++
                } else {
                    lastReadyUrl = probe.url
                    consecutiveReady = 1
                }
                if (consecutiveReady >= 2) return
            } else {
                consecutiveReady = 0
                lastReadyUrl = ""
            }
            delay(450L)
        }
    }

    private suspend fun checkKeyNow(ctrl: FindInPageController): Boolean {
        val clipboardCandidate = readKeyFromClipboard()
        if (clipboardCandidate != null) {
            onValidKeyCaptured(clipboardCandidate, fromClipboard = true)
            return true
        }

        val bodyText = ctrl.readBodyInnerText()
        val domCandidate = ApiKeyFormat.extractFirstGeminiKey(bodyText)
        if (domCandidate != null) {
            onValidKeyCaptured(domCandidate, fromClipboard = false)
            return true
        }
        return false
    }

    private suspend fun readKeyFromClipboard(): String? = withContext(Dispatchers.Main) {
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@withContext null
            val clip = cm.primaryClip ?: return@withContext null
            for (i in 0 until clip.itemCount) {
                val txt = clip.getItemAt(i)?.coerceToText(appContext)?.toString()?.trim().orEmpty()
                val extracted = ApiKeyFormat.extractFirstGeminiKey(txt)
                if (extracted != null) return@withContext extracted
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun scrubClipboardAfterCapture(keyLength: Int) = withContext(Dispatchers.Main) {
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@withContext
            val masked = "\u2022".repeat(keyLength.coerceIn(24, 40))
            val clip = ClipData.newPlainText("API Key", masked).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
            }
            cm.setPrimaryClip(clip)
        } catch (_: Throwable) {
        }
    }

    private suspend fun onValidKeyCaptured(key: String, fromClipboard: Boolean) {
        val email = _state.value.detectedEmail.ifBlank { null }
        val saved = ApiKeyVault.saveUserApiKey(appContext, key, email)
        if (!saved) return

        if (fromClipboard) {
            scrubClipboardAfterCapture(key.length)
        }

        val verifyResult = ApiKeyVault.verifyApiKeyWithGemini(appContext, key)
        if (verifyResult.kind == ApiKeyVault.KeyVerifyKind.INVALID) {
            ApiKeyVault.clearUserKeyOnly(appContext)
            _state.value = _state.value.copy(
                status = ProvisioningStatus.PROVISIONING_BACKGROUND,
                keyProvisioned = false,
                showBrowserToUser = false,
                statusDetail = "Captured key rejected, looking for a working key"
            )
            return
        }

        withContext(Dispatchers.Main) {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Throwable) {
            }
        }

        _state.value = _state.value.copy(
            status = ProvisioningStatus.KEY_READY,
            keyProvisioned = true,
            showBrowserToUser = false,
            statusDetail = "Personal API key ready"
        )
        val onboardingDone = com.princeniithompson.rapcha.data.OnboardingRepository.isOnboardingCompleted.value
        if (onboardingDone && verifyResult.kind == ApiKeyVault.KeyVerifyKind.VALID) {
            syncProfileAndRateLimitsSilently()
        } else {
            parkWebViewInStandby()
        }
    }

    private suspend fun parkWebViewInStandby() = withContext(Dispatchers.Main) {
        unregisterClipboardListener()
        automationJob?.cancel()
        automationJob = null
        webView?.let { wv ->
            try {
                wv.stopLoading()
                wv.onPause()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun runBridgeACheckboxesAndContinue(ctrl: FindInPageController): Boolean {
        val checkboxes = ctrl.scanCheckboxes()
        if (checkboxes.isEmpty()) return false

        val continueMatches = ctrl.findInteractiveMatches("continue")
        val continueDisabled = continueMatches.firstOrNull()?.disabled ?: true

        val requiredUnchecked = checkboxes.filter { !it.checked && it.required }
        val targets = if (requiredUnchecked.isNotEmpty()) {
            requiredUnchecked
        } else if (continueDisabled) {
            listOfNotNull(checkboxes.firstOrNull { !it.checked })
        } else {
            emptyList()
        }

        if (targets.isEmpty() && continueMatches.isEmpty()) return false

        for (cb in targets) {
            val target = if (cb.inViewport) cb else ctrl.scrollCheckboxIntoViewAndRemeasure(cb.index) ?: cb
            ctrl.dispatchRealTap(target.nx, target.ny)
            delay(450L)
        }

        val freshContinue = ctrl.findInteractiveMatches("continue").firstOrNull { !it.disabled }
        if (freshContinue != null) {
            val readyContinue = if (freshContinue.inViewport) {
                freshContinue
            } else {
                ctrl.scrollMatchIntoViewAndRemeasure("continue", freshContinue.index) ?: freshContinue
            }
            if (!readyContinue.disabled) {
                ctrl.dispatchRealTap(readyContinue.nx, readyContinue.ny)
                return true
            }
        }
        return targets.isNotEmpty()
    }

    private suspend fun runBridgeBSettledRouteGuard(
        wv: WebView,
        ctrl: FindInPageController
    ): Boolean {
        val url = withContext(Dispatchers.Main) { wv.url.orEmpty() }
        if (!ProvisioningWebMath.isAiStudioUrl(url) || ProvisioningWebMath.isApiKeysRoute(url)) {
            return false
        }

        delay(900L)
        val settledUrl = withContext(Dispatchers.Main) { wv.url.orEmpty() }
        if (!ProvisioningWebMath.isAiStudioUrl(settledUrl) || ProvisioningWebMath.isApiKeysRoute(settledUrl)) {
            return false
        }

        if (getApiKeyTapCount < 2) {
            val getKeyBtn = ctrl.findInteractiveMatches("get api key").firstOrNull { !it.disabled }
            if (getKeyBtn != null) {
                val ready = if (getKeyBtn.inViewport) getKeyBtn
                else ctrl.scrollMatchIntoViewAndRemeasure("get api key", getKeyBtn.index) ?: getKeyBtn
                if (!ready.disabled) {
                    getApiKeyTapCount++
                    ctrl.dispatchRealTap(ready.nx, ready.ny)
                    return true
                }
            }
        }

        if (routeGuardLoadFallbackCount < 1) {
            routeGuardLoadFallbackCount++
            withContext(Dispatchers.Main) {
                wv.loadUrl(ProvisioningWebMath.AI_STUDIO_API_KEYS_URL)
            }
            return true
        }
        return false
    }

    private suspend fun runBridgeCExistingKeyCopy(ctrl: FindInPageController): Boolean {
        withContext(Dispatchers.Main) {
            injectClipboardCopyCapture(webView)
        }

        // 1. If the "API key details" modal (Screenshot 4) is already open, copy the key directly
        val detailsTarget = ctrl.findApiKeyDetailsCopyTarget()
        if (detailsTarget != null) {
            ctrl.dispatchRealTap(detailsTarget.nx, detailsTarget.ny)
            delay(1_100L)
            if (checkKeyNow(ctrl)) return true
        }

        // 2. Search specifically for our unique "Rapcha 30frst" key/project row (never touch paid or other keys)
        val namedRow = ctrl.findNamedKeyRowTarget(ProvisioningWebMath.TARGET_KEY_NAME)
        if (namedRow != null) {
            ctrl.dispatchRealTap(namedRow.nx, namedRow.ny)
            delay(1_100L)
            if (checkKeyNow(ctrl)) return true

            // Tapping the "Rapcha 30frst" row opens the "API key details" modal (Screenshot 4)
            val openedDetailsTarget = ctrl.findApiKeyDetailsCopyTarget()
            if (openedDetailsTarget != null) {
                ctrl.dispatchRealTap(openedDetailsTarget.nx, openedDetailsTarget.ny)
                delay(1_100L)
                if (checkKeyNow(ctrl)) return true
            }
        }
        return false
    }

    private suspend fun runBridgeDCreateKeyModal(ctrl: FindInPageController): Boolean {
        val targetName = ProvisioningWebMath.TARGET_KEY_NAME

        // 1. Handle the "Create a new project" sub-modal (Screenshot 3: "Name your project" -> "Create project")
        val bodyLower = ctrl.readBodyInnerText().lowercase()
        if (bodyLower.contains("create a new project") || bodyLower.contains("name your project")) {
            ctrl.setDialogInputText(targetValue = targetName, labelHint = "project")
            delay(300L)
            val createProjSubmit = ctrl.findInteractiveMatches("create project")
                .lastOrNull { !it.disabled && !it.label.trim().startsWith("+") }
                ?: ctrl.findInteractiveMatches("create project").lastOrNull { !it.disabled }
            if (createProjSubmit != null) {
                val readyProjBtn = if (createProjSubmit.inViewport) {
                    createProjSubmit
                } else {
                    ctrl.scrollMatchIntoViewAndRemeasure("create project", createProjSubmit.index)
                        ?: createProjSubmit
                }
                ctrl.dispatchRealTap(readyProjBtn.nx, readyProjBtn.ny)
                // Wait for Google Cloud / AI Studio to finish creating the project and return to the key modal
                delay(3_200L)
                return true
            }
        }

        // 2. Handle the "Create a new key" modal (Screenshot 2: "Name your key" + "Choose an imported project")
        val createKeyMatches = ctrl.findInteractiveMatches(
            query = "create key",
            excludePhrases = listOf("create a new key", "create api key")
        )
        if (createKeyMatches.isEmpty()) return false

        // Always replace "Gemini API Key" (or blank) in "Name your key" with "Rapcha 30frst"
        ctrl.setDialogInputText(targetValue = targetName, labelHint = "key")
        delay(250L)

        // Check if "Rapcha 30frst" is already the selected project in the dropdown
        val (alreadySelected, dropdownSelector) = ctrl.findProjectSelectorInCreateKeyModal(targetName)
        val projectDropdown = dropdownSelector
            ?: ctrl.findInteractiveMatches("choose an imported project").firstOrNull { !it.disabled }
            ?: ctrl.findInteractiveMatches("select a cloud project").firstOrNull { !it.disabled }

        if (!alreadySelected && projectDropdown != null) {
            ctrl.dispatchRealTap(projectDropdown.nx, projectDropdown.ny)
            delay(750L)

            // 2a. First check if "Rapcha 30frst" already exists in the dropdown list
            val existingTargetProj = ctrl.findInteractiveMatches(targetName).firstOrNull { !it.disabled }
            if (existingTargetProj != null) {
                val readyOpt = if (existingTargetProj.inViewport) {
                    existingTargetProj
                } else {
                    ctrl.scrollMatchIntoViewAndRemeasure(targetName, existingTargetProj.index)
                        ?: existingTargetProj
                }
                ctrl.dispatchRealTap(readyOpt.nx, readyOpt.ny)
                delay(500L)
            } else {
                // 2b. Tap "+ Create project" (red arrow in Screenshot 2) to create the "Rapcha 30frst" project
                val createProjectOption = ctrl.findInteractiveMatches("create project").firstOrNull { !it.disabled }
                if (createProjectOption != null) {
                    ctrl.dispatchRealTap(createProjectOption.nx, createProjectOption.ny)
                    delay(900L)
                    // Fill "Create a new project" sub-modal (Screenshot 3) right away
                    ctrl.setDialogInputText(targetValue = targetName, labelHint = "project")
                    delay(300L)
                    val submitNewProj = ctrl.findInteractiveMatches("create project")
                        .lastOrNull { !it.disabled && !it.label.trim().startsWith("+") }
                        ?: ctrl.findInteractiveMatches("create project").lastOrNull { !it.disabled }
                    if (submitNewProj != null) {
                        ctrl.dispatchRealTap(submitNewProj.nx, submitNewProj.ny)
                        delay(3_200L)
                    }
                } else {
                    // Fallback only if Google account cannot create any more projects
                    val fallbackOption = scanFirstVisibleOption(ctrl)
                    if (fallbackOption != null) {
                        ctrl.dispatchRealTap(fallbackOption.nx, fallbackOption.ny)
                        delay(400L)
                    }
                }
            }
        }

        // Ensure "Name your key" still holds "Rapcha 30frst" before tapping "Create key"
        ctrl.setDialogInputText(targetValue = targetName, labelHint = "key")
        delay(200L)

        val submitBtn = ctrl.findInteractiveMatches(
            query = "create key",
            excludePhrases = listOf("create a new key", "create api key")
        ).firstOrNull { !it.disabled } ?: return false

        val readyBtn = if (submitBtn.inViewport) {
            submitBtn
        } else {
            ctrl.scrollMatchIntoViewAndRemeasure(
                "create key",
                submitBtn.index,
                listOf("create a new key", "create api key")
            ) ?: submitBtn
        }
        if (!readyBtn.disabled) {
            ctrl.dispatchRealTap(readyBtn.nx, readyBtn.ny)
            delay(1_400L)
            if (checkKeyNow(ctrl)) return true
            if (runBridgeCExistingKeyCopy(ctrl)) return true
            return true
        }
        return false
    }

    private suspend fun scanFirstVisibleOption(ctrl: FindInPageController): FindInPageMatch? {
        val js = """
            (function() {
                var vw = window.innerWidth || document.documentElement.clientWidth || 1;
                var vh = window.innerHeight || document.documentElement.clientHeight || 1;
                var opts = document.querySelectorAll('mat-option, [role="option"]');
                for (var i = 0; i < opts.length; i++) {
                    var el = opts[i];
                    var r = el.getBoundingClientRect();
                    var style = window.getComputedStyle(el);
                    if (r.width >= 10 && r.height >= 10 && style.display !== 'none' && style.visibility !== 'hidden') {
                        var cx = r.left + r.width / 2;
                        var cy = r.top + r.height / 2;
                        return JSON.stringify({
                            nx: Math.max(0, Math.min(1000, Math.round((cx / vw) * 1000))),
                            ny: Math.max(0, Math.min(1000, Math.round((cy / vh) * 1000)))
                        });
                    }
                }
                return '';
            })();
        """.trimIndent()
        val raw = ctrl.evaluateJavascript(js)
        val clean = try {
            org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (clean.isBlank()) return null
        return try {
            val obj = org.json.JSONObject(clean)
            FindInPageMatch(
                index = 0,
                label = "option",
                nx = obj.optInt("nx", 0),
                ny = obj.optInt("ny", 0),
                inViewport = true,
                disabled = false,
                tagName = "MAT-OPTION"
            )
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun runBridgeECreateApiKeyButton(ctrl: FindInPageController): Boolean {
        val btn = ctrl.findInteractiveMatches("create api key")
            .firstOrNull { !it.disabled } ?: return false
        val ready = if (btn.inViewport) {
            btn
        } else {
            ctrl.scrollMatchIntoViewAndRemeasure("create api key", btn.index) ?: btn
        }
        if (!ready.disabled) {
            ctrl.dispatchRealTap(ready.nx, ready.ny)
            return true
        }
        return false
    }

    fun tearDownWebView() {
        unregisterClipboardListener()
        automationJob?.cancel()
        automationJob = null
        val wv = webView ?: return
        webView = null
        controller = null
        _activeWebView.value = null
        val destroyAction = Runnable {
            try {
                wv.stopLoading()
                wv.onPause()
                wv.clearHistory()
                wv.loadUrl("about:blank")
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.removeAllViews()
                wv.destroy()
            } catch (_: Throwable) {
            }
        }
        mainHandler.postDelayed(destroyAction, 120L)
    }
}
