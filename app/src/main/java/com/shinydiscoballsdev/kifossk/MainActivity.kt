package com.shinydiscoballsdev.kifossk

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.net.URLEncoder

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private var settingsFragment: SettingsFragment? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private val fileChooserResultCode = 1

    private lateinit var noPagePinger: NoPagePinger
    private var activityResumed = false
    private var activityHasWindowFocus = false
    private var noPageAssetPageFinished = false
    private var mainFrameLoading = false
    private var recoveryNavigationPending = false
    private var handingWebViewToRecreatedActivity = false

    // Screen-space IME obstruction; unlike adjustResize, the WebView retains its size.
    private var keyboardIsVisible = false
    private var keyboardInsetBottomPx = 0
    private var keyboardLiftUpdateScheduled = false
    private var lastLiftOcclusion = Int.MIN_VALUE
    private var lastLiftHeight = Int.MIN_VALUE
    private var lastLiftTarget: WebView? = null
    private val keyboardLiftUpdate = Runnable {
        keyboardLiftUpdateScheduled = false
        pushKeyboardLiftToWebView()
    }
    private val keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (keyboardIsVisible) scheduleKeyboardLiftUpdate()
    }

    private val diagnosticsEnabled: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0


    // Retain the download bridge with the live WebView so in-flight downloads
    // continue using the same output stream across Activity recreation.
    private data class RetainedBrowser(
        val view: WebView,
        val downloads: BlobDownloaderInterface
    )
    private var retainedDownloadBridge: BlobDownloaderInterface? = null
    private val blobDownloader: BlobDownloaderInterface
        get() = retainedDownloadBridge ?: BlobDownloaderInterface(applicationContext).also {
            retainedDownloadBridge = it
        }

    companion object {
        const val EXTRA_RELOAD = "extra_reload"
        private const val TAG = "MainActivity"
        private const val SETTINGS_TAG = "kiTavern_settings_overlay"
        private const val STATE_WEBVIEW = "kiTavern_webview_state"
        private const val STATE_LAST_URL = "kiTavern_last_url"
        private const val NO_PAGE_ASSET_URL = "file:///android_asset/nopage.html"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (permission, granted) ->
            Log.d("Permissions", "$permission: ${if (granted) "granted" else "denied"}")
        }
        completePendingWebPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()

        configureKeyboardInsets()
        container = FrameLayout(this)
        container.setBackgroundColor(Color.parseColor("#1a1a2e"))
        setContentView(container)
        installKeyboardInsetsListener()
        // Window panning can move the WebView without changing IME inset height.
        // Observe the Activity container (not the retained WebView) for those moves.
        container.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)

        // A restored settings overlay must be rebound to this Activity instance.
        settingsFragment = supportFragmentManager.findFragmentByTag(SETTINGS_TAG) as? SettingsFragment
        settingsFragment?.let(::bindSettingsCallbacks)

        noPagePinger = NoPagePinger(
            targetUrlProvider = { currentNoPageRetryUrl() },
            shouldPing = { shouldPingNoPageTarget() },
            onSuccessfulPing = { target -> recoverFromNoPageIfIdle(target) }
        )

        setupWebView(savedInstanceState)

        // Start independently of the Activity lifecycle. Never stop the service in onDestroy().
        if (KioskPrefs.getKeepAlive(this)) {
            KeepAliveService.start(this)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsFragment != null) closeSettings() else showSettings()
            }
        })
    }

    private fun applyTheme() {
        val theme = KioskPrefs.getTheme(this)
        setTheme(if (theme == "light") R.style.Theme_KioskViewer_Light else R.style.Theme_KioskViewer)
    }

    /**
     * Trial of the Stack Overflow adjustPan + page-side keyboard positioning.
     * Do not combine it with the old native IME padding/adjustResize behaviour.
     * We retain edge-to-edge for the existing native Settings presentation.
     */
    private fun configureKeyboardInsets() {
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun installKeyboardInsetsListener() {
        val root = findViewById<FrameLayout>(android.R.id.content)
        val barsAndCutout = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout()
        val handledTypes = barsAndCutout or WindowInsetsCompat.Type.ime()

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(barsAndCutout)
            // Keep only system-bar padding for both WebView and the native overlay.
            // IME padding here would shrink WebView and double-count the JS adjustment.
            if (view.paddingLeft != bars.left || view.paddingTop != bars.top ||
                view.paddingRight != bars.right || view.paddingBottom != bars.bottom
            ) {
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }

            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val bottom = if (visible) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            if (keyboardIsVisible != visible || keyboardInsetBottomPx != bottom) {
                keyboardIsVisible = visible
                keyboardInsetBottomPx = bottom
                scheduleKeyboardLiftUpdate()
            }

            // We handle IME occlusion in JavaScript. Tell WebView that this inset
            // has been handled, avoiding modern WebView's second viewport resize.
            WindowInsetsCompat.Builder(insets)
                .setInsets(handledTypes, Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false)
                .build()
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun scheduleKeyboardLiftUpdate() {
        if (!::container.isInitialized) return
        // Coalesce IME and layout callbacks into at most one update per frame.
        if (!keyboardLiftUpdateScheduled) {
            keyboardLiftUpdateScheduled = true
            container.postOnAnimation(keyboardLiftUpdate)
        }
    }

    /** In screen coordinates so adjustPan's own window movement is not counted twice. */
    private fun getWindowScreenBottomPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.bounds.bottom
        }
        val size = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(size)
        return size.y
    }

    private fun pushKeyboardLiftToWebView() {
        if (!::webView.isInitialized || !webView.isAttachedToWindow ||
            !UrlValidator.isValid(webView.url) || webView.height <= 0
        ) return

        // Calculate only the part of the *currently positioned* WebView actually
        // hidden by the keyboard. This avoids blindly moving the bar by the full
        // IME height if Android has already panned the window upward.
        val location = IntArray(2)
        webView.getLocationOnScreen(location)
        val obscuredPx = if (keyboardIsVisible && settingsFragment == null) {
            val keyboardTop = getWindowScreenBottomPx() - keyboardInsetBottomPx
            (location[1] + webView.height - keyboardTop).coerceIn(0, webView.height)
        } else {
            0
        }
        // Avoid invoking JavaScript for every unrelated global layout pass.
        if (lastLiftTarget === webView && lastLiftOcclusion == obscuredPx &&
            lastLiftHeight == webView.height
        ) return
        lastLiftTarget = webView
        lastLiftOcclusion = obscuredPx
        lastLiftHeight = webView.height
        webView.evaluateJavascript(
            "window.__kiTavernKeyboardLift && " +
                "window.__kiTavernKeyboardLift.setOcclusion($obscuredPx, ${webView.height});",
            null
        )
    }

    private fun invalidateKeyboardLift() {
        lastLiftTarget = null
        lastLiftOcclusion = Int.MIN_VALUE
        lastLiftHeight = Int.MIN_VALUE
    }

    /** Injected only into normal HTTP(S) pages; nopage.html needs no keyboard code. */
    private fun installKeyboardLift(target: WebView) {
        if (!UrlValidator.isValid(target.url)) return
        val script = """
            (function () {
                if (window.__kiTavernKeyboardLift) return;
                var hiddenPx = 0;
                var viewHeightPx = 0;
                var adjustedElement = null;
                var originalTranslate = '';
                var lastLift = 0;
                var scheduled = false;

                function reset() {
                    if (adjustedElement) adjustedElement.style.translate = originalTranslate;
                    adjustedElement = null;
                    originalTranslate = '';
                    lastLift = 0;
                }

                function positionComposer() {
                    scheduled = false;
                    var input = document.getElementById('send_textarea');
                    var bar = document.getElementById('form_sheld') ||
                              document.getElementById('send_form');
                    var focused = input && (document.activeElement === input ||
                                             input.contains(document.activeElement));
                    if (!focused || !bar || !bar.isConnected || hiddenPx <= 0 ||
                        viewHeightPx <= 0) {
                        reset();
                        return;
                    }

                    if (adjustedElement !== bar) {
                        reset();
                        adjustedElement = bar;
                        originalTranslate = bar.style.translate || '';
                    }

                    // Convert measured Android screen pixels to page CSS pixels.
                    // Using the actual WebView height accounts for the page's scale.
                    var pageHeight = window.innerHeight;
                    var hiddenCss = hiddenPx * (pageHeight / viewHeightPx);
                    var usableBottom = pageHeight - hiddenCss;
                    if (window.visualViewport) {
                        usableBottom = Math.min(usableBottom,
                            window.visualViewport.offsetTop + window.visualViewport.height);
                    }
                    usableBottom -= 10; // small gap above the keyboard

                    // getBoundingClientRect includes our previous lift. Add it back
                    // before computing the next lift so repeated events don't drift.
                    var naturalBottom = bar.getBoundingClientRect().bottom + lastLift;
                    var lift = Math.max(0, Math.ceil(naturalBottom - usableBottom));
                    lift = Math.min(lift, Math.max(0, pageHeight - 64));
                    if (lift !== lastLift) {
                        lastLift = lift;
                        bar.style.translate = lift > 0 ? ('0px -' + lift + 'px') : originalTranslate;
                    }
                }

                function schedule() {
                    if (scheduled) return;
                    scheduled = true;
                    window.requestAnimationFrame(positionComposer);
                }

                window.__kiTavernKeyboardLift = {
                    setOcclusion: function (pixels, height) {
                        hiddenPx = Math.max(0, Number(pixels) || 0);
                        viewHeightPx = Math.max(0, Number(height) || 0);
                        schedule();
                    }
                };
                document.addEventListener('focusin', schedule, true);
                document.addEventListener('focusout', function () { setTimeout(schedule, 0); }, true);
                window.addEventListener('resize', schedule);
                if (window.visualViewport) {
                    window.visualViewport.addEventListener('resize', schedule);
                    window.visualViewport.addEventListener('scroll', schedule);
                }
            })();
        """.trimIndent()
        target.evaluateJavascript(script) { _ ->
            if (::webView.isInitialized && webView === target) scheduleKeyboardLiftUpdate()
        }
    }

    /** Applies runtime preferences without navigating/reloading the WebView. */
    private fun applyRuntimeSettings() {
        val wantedOrientation = when (KioskPrefs.getOrientation(this)) {
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != wantedOrientation) requestedOrientation = wantedOrientation

        if (KioskPrefs.getScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun isCurrentPageOrigin(origin: Uri): Boolean {
        if (!::webView.isInitialized) return false
        val page = Uri.parse(webView.url ?: return false)
        val firstScheme = page.scheme?.lowercase() ?: return false
        if (firstScheme != "http" && firstScheme != "https") return false
        if (!firstScheme.equals(origin.scheme, ignoreCase = true) ||
            !page.host.equals(origin.host, ignoreCase = true)
        ) return false
        fun effectivePort(uri: Uri): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        return effectivePort(page) == effectivePort(origin)
    }

    private fun requiredAndroidMediaPermissions(request: PermissionRequest): List<String> =
        request.resources.mapNotNull { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> null
            }
        }.distinct()

    private fun completePendingWebPermission() {
        val request = pendingWebPermission ?: return
        pendingWebPermission = null
        if (!isCurrentPageOrigin(request.origin)) {
            request.deny()
            return
        }
        val allowed = request.resources.filter { resource ->
            val permission = when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> null
            }
            permission != null && ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        // Request camera/microphone only if a webpage actually asks for them.
        // This avoids prompts and system work on an ordinary kiosk launch.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        if (permissions.isNotEmpty()) requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun setupWebView(savedInstanceState: Bundle?) {
        applyRuntimeSettings()
        val forceReload = intent?.getBooleanExtra(EXTRA_RELOAD, false) == true
        val retained = lastCustomNonConfigurationInstance as? RetainedBrowser
        retainedDownloadBridge = retained?.downloads
        val retainedWebView = retained?.view
        webView = createConfiguredWebView(retainedWebView)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // A retained/restored WebView may never fire onPageFinished again.
        webView.post { installKeyboardLift(webView) }

        // If this is a configuration/recreation hand-off, keep the same WebView object
        // and its live DOM/JS heap. An explicit EXTRA_RELOAD still overrides retention.
        if (retainedWebView != null) {
            mainFrameLoading = retainedWebView.progress < 100
            noPageAssetPageFinished = !forceReload && !mainFrameLoading &&
                isNoPageAssetUrl(retainedWebView.url)
            if (forceReload) webView.loadUrl(KioskPrefs.getUrl(this))
            intent?.removeExtra(EXTRA_RELOAD)
            updateNoPagePinger()
            return
        }

        val previousWebViewState = savedInstanceState?.getBundle(STATE_WEBVIEW)
        val restoredHistory = if (!forceReload && previousWebViewState != null) {
            try {
                webView.restoreState(previousWebViewState)
            } catch (e: Exception) {
                Log.w(TAG, "WebView navigation state could not be restored", e)
                null
            }
        } else {
            null
        }

        when {
            forceReload -> webView.loadUrl(KioskPrefs.getUrl(this))
            restoredHistory == null || webView.url.isNullOrBlank() -> {
                val savedUrl = savedInstanceState?.getString(STATE_LAST_URL)
                    ?.takeIf { UrlValidator.isValid(it) || isNoPageAssetUrl(it) }
                // Only navigate if WebView state restoration failed. Normal Activity
                // resume/return-to-app paths never reload the current SillyTavern page.
                webView.loadUrl(savedUrl ?: KioskPrefs.getUrl(this))
            }
            else -> Log.d(TAG, "Restored WebView back/forward state at ${webView.url}")
        }

        // Restoring history may not produce another onPageFinished() callback.
        // Resume checking if the local fallback is already fully displayed.
        if (!forceReload && restoredHistory != null &&
            isNoPageAssetUrl(webView.url) && webView.progress >= 100
        ) {
            mainFrameLoading = false
            noPageAssetPageFinished = true
        }

        // Do not let a one-shot explicit reload extra cause another reload on recreation.
        intent?.removeExtra(EXTRA_RELOAD)
        updateNoPagePinger()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(existing: WebView? = null): WebView {
        // A mutable context prevents our explicitly retained WebView from pinning an
        // obsolete Activity after configuration/theme recreation.
        val view = existing ?: WebView(MutableContextWrapper(this))
        (view.context as? MutableContextWrapper)?.baseContext = this
        view.setBackgroundColor(Color.parseColor("#1a1a2e"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Keep the renderer at important priority even when this WebView is not visible.
            // This reduces (but cannot eliminate) renderer process reclamation.
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.addJavascriptInterface(blobDownloader, "BlobDownloader")

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val requestedUrl = request?.url?.toString() ?: return false

                if (requestedUrl.startsWith("blob:")) {
                    handleBlobDownload(view, requestedUrl)
                    return true
                }

                if (requestedUrl.startsWith("data:")) {
                    handleDataUrlDownload(requestedUrl)
                    return true
                }

                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                invalidateKeyboardLift()
                // Only ping when the local fallback has fully loaded and is idle.
                mainFrameLoading = true
                recoveryNavigationPending = false
                noPageAssetPageFinished = false
                updateNoPagePinger()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null) {
                    injectBlobInterceptor(view)
                    installKeyboardLift(view)
                    installDebugHeartbeat(view)
                }
                mainFrameLoading = false
                recoveryNavigationPending = false
                noPageAssetPageFinished = isNoPageAssetUrl(url ?: view?.url)
                updateNoPagePinger()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Subresource failures must not replace the entire page with nopage.html.
                if (request?.isForMainFrame != true) return

                val failedUrl = request.url.toString()
                val encodedUrl = URLEncoder.encode(failedUrl, Charsets.UTF_8.name())
                view?.loadUrl("$NO_PAGE_ASSET_URL?url=$encodedUrl")
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.e(TAG, "WebView renderer exited; didCrash=${detail.didCrash()}")
                recoverWebViewAfterRendererExit(view)
                // Returning true says that this Activity handled the dead WebView.
                return true
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                if (diagnosticsEnabled && message.message().startsWith("[kiTavern-heartbeat]")) {
                    Log.w("KiTavernDiagnostics", message.message())
                    return true
                }
                return super.onConsoleMessage(message)
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // onPageFinished can run before WebView reaches 100% progress.
                // Start recovery probes once both signals say the page is idle.
                if (newProgress == 100 && noPageAssetPageFinished && !mainFrameLoading) {
                    updateNoPagePinger()
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val chooserIntent = try {
                    fileChooserParams?.createIntent()
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to create file chooser intent", e)
                    null
                }

                if (chooserIntent == null) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }

                return try {
                    startActivityForResult(chooserIntent, fileChooserResultCode)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to open file chooser", e)
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread {
                    // Grant media only to the currently displayed top-level origin,
                    // not arbitrary embedded third-party frames.
                    if (!isCurrentPageOrigin(request.origin)) {
                        request.deny()
                        return@runOnUiThread
                    }
                    pendingWebPermission?.deny()
                    pendingWebPermission = request
                    val missing = requiredAndroidMediaPermissions(request).filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) completePendingWebPermission()
                    else requestPermissionLauncher.launch(missing.toTypedArray())
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (request != null && pendingWebPermission === request) {
                    pendingWebPermission = null
                }
            }
        }

        // Observe a slow renderer, but NEVER terminate it just because long-running
        // JavaScript made it temporarily unresponsive.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.setWebViewRenderProcessClient(object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(
                    view: WebView, renderer: WebViewRenderProcess?
                ) {
                    Log.w(TAG, "WebView renderer unresponsive (work may still be running)")
                }

                override fun onRenderProcessResponsive(
                    view: WebView, renderer: WebViewRenderProcess?
                ) {
                    Log.i(TAG, "WebView renderer responsive again")
                }
            })
        }

        view.setDownloadListener(DownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            if (downloadUrl.startsWith("blob:")) return@DownloadListener
            if (downloadUrl.startsWith("data:")) {
                handleDataUrlDownload(downloadUrl)
                return@DownloadListener
            }

            val downloadUri = try {
                Uri.parse(downloadUrl)
            } catch (_: Exception) {
                return@DownloadListener
            }
            if (downloadUri.scheme !in setOf("http", "https")) return@DownloadListener

            try {
                val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
                val request = DownloadManager.Request(downloadUri).apply {
                    setMimeType(mimeType)
                    CookieManager.getInstance().getCookie(downloadUrl)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { addRequestHeader("cookie", it) }
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Downloading...")
                    setTitle(fileName)
                    allowScanningByMediaScanner()
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                Toast.makeText(applicationContext, "Downloading file...", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Download could not be queued", e)
                Toast.makeText(applicationContext, "Unable to start download", Toast.LENGTH_LONG).show()
            }
        })

        return view
    }

    private fun recoverWebViewAfterRendererExit(deadWebView: WebView) {
        noPageAssetPageFinished = false
        mainFrameLoading = false
        recoveryNavigationPending = false
        invalidateKeyboardLift()
        val urlToRestore = deadWebView.url
            ?.takeIf { UrlValidator.isValid(it) || isNoPageAssetUrl(it) }
            ?: KioskPrefs.getUrl(this)

        (deadWebView.parent as? ViewGroup)?.removeView(deadWebView)
        // A terminated renderer cannot finish its active Blob transfers.
        retainedDownloadBridge?.cancelAllDownloads()
        try {
            deadWebView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Could not destroy crashed WebView", e)
        }

        if (!::container.isInitialized || isFinishing || isDestroyed) return
        webView = createConfiguredWebView()
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // This reload is intentional: the previous renderer is dead and cannot retain live JS state.
        webView.loadUrl(urlToRestore)
        updateNoPagePinger()
    }

    private fun handleBlobDownload(targetView: WebView?, blobUrl: String) {
        val quotedUrl = JSONObject.quote(blobUrl)
        targetView?.evaluateJavascript(
            """
            (function() {
                var blob = window.__blobRegistry && window.__blobRegistry[$quotedUrl];
                function saveBlob(b) {
                    if (window.__kiTavernSaveBlob) window.__kiTavernSaveBlob(b, 'download');
                }
                if (blob) saveBlob(blob);
                else fetch($quotedUrl).then(function(r) { return r.blob(); })
                    .then(saveBlob).catch(function(e) { console.warn('Blob fetch failed', e); });
            })();
            """.trimIndent(), null
        )
    }

    private fun handleDataUrlDownload(dataUrl: String) {
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex <= 0) return
        try {
            val header = dataUrl.substring(0, commaIndex).removePrefix("data:")
            val payload = dataUrl.substring(commaIndex + 1)
            val mimeType = header.substringBefore(';').ifBlank { "text/plain" }
            val name = "download" + mimeToExtension(mimeType)
            if (header.split(';').any { it.equals("base64", ignoreCase = true) }) {
                blobDownloader.downloadBase64(payload, mimeType, name)
            } else {
                blobDownloader.downloadText(Uri.decode(payload), mimeType, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not process data URL download", e)
            Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectBlobInterceptor(targetView: WebView) {
        val js = """
            (function() {
                if (window.__blobInterceptorInstalled) return;
                window.__blobInterceptorInstalled = true;
                window.__blobRegistry = window.__blobRegistry || {};

                var create = URL.createObjectURL;
                URL.createObjectURL = function(blob) {
                    var url = create.call(this, blob);
                    window.__blobRegistry[url] = blob;
                    return url;
                };
                var revoke = URL.revokeObjectURL;
                URL.revokeObjectURL = function(url) {
                    delete window.__blobRegistry[url];
                    return revoke.call(this, url);
                };

                // Stream Blob slices across the bridge. Do not materialize the whole
                // file as a Base64 string in JavaScript and again in Kotlin.
                window.__kiTavernSaveBlob = function(blob, filename) {
                    var token = BlobDownloader.beginDownload(blob.type || 'application/octet-stream',
                        filename || 'download');
                    if (!token) return;
                    var offset = 0;
                    var chunkSize = 192 * 1024;
                    function abort() { BlobDownloader.cancelDownload(token); }
                    function next() {
                        if (offset >= blob.size) {
                            BlobDownloader.finishDownload(token);
                            return;
                        }
                        var slice = blob.slice(offset, offset + chunkSize);
                        var reader = new FileReader();
                        reader.onload = function() {
                            var result = reader.result || '';
                            var separator = result.indexOf(',');
                            if (separator < 0 || !BlobDownloader.appendChunk(token,
                                    result.substring(separator + 1))) {
                                abort();
                                return;
                            }
                            offset += slice.size;
                            setTimeout(next, 0);
                        };
                        reader.onerror = abort;
                        reader.onabort = abort;
                        reader.readAsDataURL(slice);
                    }
                    next();
                };

                function interceptDownload(element) {
                    var href = element.getAttribute('href');
                    if (!href || href.indexOf('blob:') !== 0) return false;
                    var blob = window.__blobRegistry[href];
                    var filename = element.getAttribute('download') || 'download';
                    function saveBlob(b) { window.__kiTavernSaveBlob(b, filename); }
                    if (blob) saveBlob(blob);
                    else fetch(href).then(function(r) { return r.blob(); })
                        .then(saveBlob).catch(function(e) { console.warn('Blob fetch failed', e); });
                    return true;
                }

                document.addEventListener('click', function(event) {
                    var target = event.target;
                    var el = target && target.closest ? target.closest('a[download]') : null;
                    if (!el || !interceptDownload(el)) return;
                    event.preventDefault();
                    event.stopPropagation();
                }, true);
                var click = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    if (this.hasAttribute('download') && interceptDownload(this)) return;
                    return click.call(this);
                };
            })();
        """.trimIndent()
        targetView.evaluateJavascript(js, null)
    }

    /** Development-only timer-delay diagnostics; never a watchdog or reload trigger. */
    private fun installDebugHeartbeat(target: WebView) {
        if (!diagnosticsEnabled || !UrlValidator.isValid(target.url)) return
        target.evaluateJavascript(
            """
            (function() {
                if (window.__kiTavernHeartbeat) return;
                var last = Date.now();
                window.__kiTavernHeartbeat = setInterval(function() {
                    var now = Date.now(), elapsed = now - last;
                    last = now;
                    if (elapsed > 25000)
                        console.warn('[kiTavern-heartbeat] delay=' + elapsed +
                            'ms visibility=' + document.visibilityState);
                }, 10000);
            })();
            """.trimIndent(), null
        )
    }

    private fun mimeToExtension(mime: String): String = when (mime.lowercase()) {
        "text/json", "application/json" -> ".json"
        "text/plain" -> ".txt"
        "text/html" -> ".html"
        "image/png" -> ".png"
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "application/pdf" -> ".pdf"
        "application/zip" -> ".zip"
        else -> ""
    }

    private fun isNoPageAssetUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(url)
            uri.scheme.equals("file", ignoreCase = true) &&
                uri.path?.endsWith("/android_asset/nopage.html") == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The fallback page records the URL that actually failed, which may be a
     * SillyTavern deep link rather than the configured homepage. Retry that URL
     * when possible; do not navigate back to the local error asset.
     * This provider is called only on the main thread by NoPagePinger.
     */
    private fun currentNoPageRetryUrl(): String {
        val configuredUrl = KioskPrefs.getUrl(this)
        if (!::webView.isInitialized || !isNoPageAssetUrl(webView.url)) return configuredUrl
        return try {
            Uri.parse(webView.url).getQueryParameter("url")
                ?.takeIf { UrlValidator.isValid(it) }
                ?: configuredUrl
        } catch (_: Exception) {
            configuredUrl
        }
    }

    private fun shouldPingNoPageTarget(): Boolean {
        return activityResumed &&
            activityHasWindowFocus &&
            settingsFragment == null &&
            !isFinishing &&
            !isDestroyed &&
            noPageAssetPageFinished &&
            !mainFrameLoading &&
            !recoveryNavigationPending &&
            ::webView.isInitialized &&
            webView.isAttachedToWindow &&
            webView.progress >= 100 &&
            isNoPageAssetUrl(webView.url)
    }

    /** Runs on the UI thread, after an HTTP 2xx/3xx ping finishes. */
    private fun recoverFromNoPageIfIdle(successfulTarget: String) {
        // The fallback URL or configured URL may have changed during the ping.
        // Recheck state after the network callback to prevent stale navigation.
        if (!shouldPingNoPageTarget() || successfulTarget != currentNoPageRetryUrl()) return

        recoveryNavigationPending = true // close the race before issuing loadUrl()
        noPageAssetPageFinished = false
        updateNoPagePinger() // cancels the ticker and invalidates queued callbacks
        try {
            Log.i(TAG, "Fallback page online again; retrying original website URL")
            webView.loadUrl(successfulTarget) // never reload the local nopage.html asset
        } catch (e: Exception) {
            recoveryNavigationPending = false
            noPageAssetPageFinished = isNoPageAssetUrl(webView.url) &&
                webView.progress >= 100 && !mainFrameLoading
            updateNoPagePinger()
            Log.e(TAG, "Failed to start recovery navigation", e)
        }
    }

    private fun updateNoPagePinger() {
        if (!::noPagePinger.isInitialized) return
        if (shouldPingNoPageTarget()) noPagePinger.start() else noPagePinger.stop()
    }

    private fun bindSettingsCallbacks(fragment: SettingsFragment) {
        fragment.onDismiss = { closeSettings() }
        fragment.onReload = {
            if (::webView.isInitialized) {
                // The user's Reload button is an explicit navigation request.
                webView.loadUrl(KioskPrefs.getUrl(this))
            }
        }
        fragment.onSettingsChanged = { applyRuntimeSettings() }
    }

    private fun showSettings() {
        if (settingsFragment != null) return
        val existing = supportFragmentManager.findFragmentByTag(SETTINGS_TAG) as? SettingsFragment
        if (existing != null) {
            settingsFragment = existing
            bindSettingsCallbacks(existing)
            updateNoPagePinger()
            return
        }

        val fragment = SettingsFragment()
        settingsFragment = fragment
        scheduleKeyboardLiftUpdate()
        bindSettingsCallbacks(fragment)
        supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, SETTINGS_TAG)
            .commit()
        updateNoPagePinger()
    }

    private fun closeSettings() {
        val fragment = settingsFragment
            ?: (supportFragmentManager.findFragmentByTag(SETTINGS_TAG) as? SettingsFragment)
            ?: return
        settingsFragment = null
        scheduleKeyboardLiftUpdate()
        supportFragmentManager.beginTransaction()
            .remove(fragment)
            .runOnCommit { updateNoPagePinger() }
            .commit()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        settingsFragment?.let { if (it.handleTouchEvent(event)) return true }
        return super.dispatchTouchEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == fileChooserResultCode) {
            val results = if (data == null || resultCode != RESULT_OK) {
                null
            } else {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RELOAD, false)) {
            if (::webView.isInitialized) webView.loadUrl(KioskPrefs.getUrl(this))
            intent.removeExtra(EXTRA_RELOAD)
        }
        updateNoPagePinger()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        activityResumed = true
        updateNoPagePinger()
        // No loadUrl() here: returning from another app must not reload SillyTavern.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        activityHasWindowFocus = hasFocus
        updateNoPagePinger()
    }

    override fun onPause() {
        activityResumed = false
        updateNoPagePinger()
        // Do not call WebView.pauseTimers(): it globally pauses JS timers for all WebViews.
        // WebView.onPause() does not pause JS but can pause other background processing;
        // we avoid it here to give SillyTavern the best effort to continue working.
        super.onPause()
    }

    override fun onStop() {
        activityResumed = false
        updateNoPagePinger()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) {
            outState.putString(STATE_LAST_URL, webView.url)
            val webViewState = Bundle()
            try {
                if (webView.saveState(webViewState) != null) {
                    outState.putBundle(STATE_WEBVIEW, webViewState)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to save WebView navigation state", e)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRetainCustomNonConfigurationInstance(): Any? {
        if (!isFinishing && ::webView.isInitialized) {
            // Keep the same WebView through rotations/recreation so the current DOM and
            // JavaScript heap survive. The new Activity rebinds its clients to itself.
            (webView.parent as? ViewGroup)?.removeView(webView)
            (webView.context as? MutableContextWrapper)?.baseContext = applicationContext
            handingWebViewToRecreatedActivity = true
            return RetainedBrowser(webView, blobDownloader)
        }
        return super.onRetainCustomNonConfigurationInstance()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Diagnostic only: forcing a reload or clearing the cache here would
        // discard exactly the live page state Keep Alive aims to preserve.
        if (diagnosticsEnabled) {
            Log.w("KiTavernDiagnostics", "Android memory trim level=$level")
        }
    }

    override fun onDestroy() {
        activityResumed = false
        if (::container.isInitialized) {
            container.removeCallbacks(keyboardLiftUpdate)
            keyboardLiftUpdateScheduled = false
            val observer = container.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(keyboardLayoutListener)
        }
        invalidateKeyboardLift()
        if (::noPagePinger.isInitialized) noPagePinger.close()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        pendingWebPermission?.deny()
        pendingWebPermission = null

        if (::webView.isInitialized && !handingWebViewToRecreatedActivity) {
            try {
                webView.stopLoading()
                (webView.parent as? ViewGroup)?.removeView(webView)
                // Break native callback references before releasing the renderer.
                webView.webChromeClient = null
                webView.webViewClient = WebViewClient()
                webView.destroy()
                retainedDownloadBridge?.cancelAllDownloads()
            } catch (e: Exception) {
                Log.w(TAG, "WebView cleanup failed", e)
            }
        }

        // IMPORTANT: KeepAliveService is intentionally not stopped here.
        super.onDestroy()
    }

}
