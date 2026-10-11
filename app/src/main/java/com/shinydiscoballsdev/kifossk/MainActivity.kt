package com.shinydiscoballsdev.kifossk

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
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
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private var settingsFragment: SettingsFragment? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserResultCode = 1

    private lateinit var noPagePinger: NoPagePinger
    private var activityResumed = false
    private var activityHasWindowFocus = false
    private var noPageAssetPageFinished = false
    private var mainFrameLoading = false
    private var recoveryNavigationPending = false
    private var handingWebViewToRecreatedActivity = false

    private val blobDownloader: BlobDownloaderInterface by lazy {
        BlobDownloaderInterface(applicationContext)
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
     * Resize the content for the IME rather than reasserting fullscreen flags on
     * window focus changes (Gboard can temporarily create its own popup window).
     * The manifest should also declare adjustResize for pre-Android 11 devices.
     */
    private fun configureKeyboardInsets() {
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // We handle the content root's IME and system bar insets once, rather
        // than repeatedly hiding system bars whenever Gboard changes focus.
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun installKeyboardInsetsListener() {
        // Both the WebView and the swipeable Settings overlay are children of
        // android.R.id.content. Apply padding here so neither sits behind Gboard.
        val root = findViewById<FrameLayout>(android.R.id.content)
        val handledTypes = WindowInsetsCompat.Type.ime() or
            WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeBottom = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else {
                0
            }
            val bottom = maxOf(bars.bottom, imeBottom)
            if (view.paddingLeft != bars.left || view.paddingTop != bars.top ||
                view.paddingRight != bars.right || view.paddingBottom != bottom
            ) {
                view.setPadding(bars.left, bars.top, bars.right, bottom)
            }
            // The native parent has already applied these insets. Zero only
            // these types for children, avoiding WebView's double IME padding.
            WindowInsetsCompat.Builder(insets)
                .setInsets(handledTypes, Insets.NONE)
                .build()
        }
        ViewCompat.requestApplyInsets(root)
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

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.CAMERA
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.RECORD_AUDIO
        }
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
        val retainedWebView = lastCustomNonConfigurationInstance as? WebView
        webView = createConfiguredWebView(retainedWebView)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

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
        val view = existing ?: WebView(this)
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
                // Only ping when the local fallback has fully loaded and is idle.
                mainFrameLoading = true
                recoveryNavigationPending = false
                noPageAssetPageFinished = false
                updateNoPagePinger()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null) injectBlobInterceptor(view)
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
                    // Only grant WebView media resources for which Android runtime
                    // permissions are already granted. Do not blindly grant everything.
                    val allowedResources = request.resources.filter { resource ->
                        when (resource) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            else -> false
                        }
                    }
                    if (allowedResources.isEmpty()) request.deny() else request.grant(allowedResources.toTypedArray())
                }
            }
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
        val urlToRestore = deadWebView.url
            ?.takeIf { UrlValidator.isValid(it) || isNoPageAssetUrl(it) }
            ?: KioskPrefs.getUrl(this)

        (deadWebView.parent as? ViewGroup)?.removeView(deadWebView)
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
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        var base64 = reader.result.split(',')[1];
                        var mime = b.type || 'application/octet-stream';
                        BlobDownloader.downloadBase64(base64, mime, 'download');
                    };
                    reader.readAsDataURL(b);
                }
                if (blob) {
                    saveBlob(blob);
                } else {
                    fetch($quotedUrl).then(function(r) { return r.blob(); })
                        .then(saveBlob)
                        .catch(function(e) { console.log('Blob fetch failed', e); });
                }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun handleDataUrlDownload(dataUrl: String) {
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex <= 0) return

        try {
            val header = dataUrl.substring(0, commaIndex).removePrefix("data:")
            val encodedPayload = dataUrl.substring(commaIndex + 1)
            val mimeType = header.substringBefore(';').ifBlank { "text/plain" }
            val isBase64 = header.split(';').any { it.equals("base64", ignoreCase = true) }
            val bytes = if (isBase64) {
                Base64.decode(encodedPayload, Base64.DEFAULT)
            } else {
                Uri.decode(encodedPayload).toByteArray(Charsets.UTF_8)
            }
            val filename = "download" + mimeToExtension(mimeType)
            blobDownloader.downloadBase64(Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType, filename)
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

                var origCreateObjectURL = URL.createObjectURL;
                URL.createObjectURL = function(blob) {
                    var url = origCreateObjectURL.call(this, blob);
                    window.__blobRegistry[url] = blob;
                    return url;
                };

                var origRevokeObjectURL = URL.revokeObjectURL;
                URL.revokeObjectURL = function(url) {
                    delete window.__blobRegistry[url];
                    origRevokeObjectURL.call(this, url);
                };

                function interceptDownload(el) {
                    var href = el.getAttribute('href');
                    if (!href || !href.startsWith('blob:')) return false;
                    var blob = window.__blobRegistry[href];
                    var filename = el.getAttribute('download') || 'download';
                    function saveBlob(b) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            BlobDownloader.downloadBase64(base64, b.type || 'application/octet-stream', filename);
                        };
                        reader.readAsDataURL(b);
                    }
                    if (blob) {
                        saveBlob(blob);
                    } else {
                        fetch(href).then(function(r) { return r.blob(); })
                            .then(saveBlob)
                            .catch(function(e) { console.log('Blob fetch failed', e); });
                    }
                    return true;
                }

                document.addEventListener('click', function(e) {
                    var target = e.target;
                    var el = target && target.closest ? target.closest('a[download]') : null;
                    if (!el || !interceptDownload(el)) return;
                    e.preventDefault();
                    e.stopPropagation();
                }, true);

                var origClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    if (this.hasAttribute('download') && interceptDownload(this)) return;
                    return origClick.call(this);
                };
            })();
        """.trimIndent()
        targetView.evaluateJavascript(js, null)
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
            handingWebViewToRecreatedActivity = true
            return webView
        }
        return super.onRetainCustomNonConfigurationInstance()
    }

    override fun onDestroy() {
        activityResumed = false
        if (::noPagePinger.isInitialized) noPagePinger.close()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        if (::webView.isInitialized && !handingWebViewToRecreatedActivity) {
            try {
                webView.stopLoading()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "WebView cleanup failed", e)
            }
        }

        // IMPORTANT: KeepAliveService is intentionally not stopped here.
        super.onDestroy()
    }

    class BlobDownloaderInterface(context: Context) {
        private val appContext = context.applicationContext

        @JavascriptInterface
        fun downloadBase64(base64Data: String, mimeType: String, filename: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val safeFilename = File(filename).name.takeIf { it.isNotBlank() && it != "." && it != ".." }
                    ?: "download"
                val safeMime = mimeType.ifBlank { "application/octet-stream" }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeFilename)
                        put(MediaStore.Downloads.MIME_TYPE, safeMime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Could not create Downloads entry")
                    appContext.contentResolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
                        ?: throw IllegalStateException("Could not open Downloads output stream")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    appContext.contentResolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                        throw IllegalStateException("Could not create Downloads directory")
                    }
                    val file = File(downloadsDir, safeFilename)
                    FileOutputStream(file).use { output -> output.write(bytes) }
                    android.media.MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(file.absolutePath),
                        arrayOf(safeMime),
                        null
                    )
                }

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "Saved: $safeFilename", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("BlobDownload", "Failed to save downloaded content", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
