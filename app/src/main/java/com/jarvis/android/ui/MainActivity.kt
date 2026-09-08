package com.jarvis.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import com.jarvis.android.JarvisApp
import com.jarvis.android.R
import com.jarvis.android.Settings
import com.jarvis.android.bridge.BridgeContract
import com.jarvis.android.bridge.JarvisBridge
import com.jarvis.android.databinding.ActivityMainBinding
import com.jarvis.android.service.JarvisSessionService
import org.json.JSONObject
import java.util.Locale

/**
 * The whole app: one Activity hosting one full-screen WebView pointed at the
 * gateway that serves the Jarvis chat page.
 *
 * The page is loaded live over the network rather than bundled into assets on
 * purpose — it derives its own `wss://` endpoint from `location.host`, so it has
 * to actually be served from the gateway origin for the socket, the service
 * worker and localStorage to behave the way they do in a browser.
 */
class MainActivity : AppCompatActivity(), JarvisBridge.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var bridge: JarvisBridge
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: MutableList<Pair<String, Boolean>> = mutableListOf()

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var loadedUrl: String? = null

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingWebPermissionRequest
            pendingWebPermissionRequest = null
            if (granted) {
                // Only now does the WebView-level grant mean anything: without the
                // Android permission the capture would fail behind the page's back.
                request?.grant(request.resources)
                maybeStartSessionService()
            } else {
                request?.deny()
                Snackbar.make(binding.root, R.string.mic_denied, Snackbar.LENGTH_LONG).show()
            }
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            callback?.onReceiveValue(
                if (result.resultCode == Activity.RESULT_OK) {
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                } else {
                    null
                }
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        configureWebView()
        configureServiceWorker()

        bridge = JarvisBridge(binding.webView, main, this)
        binding.webView.addJavascriptInterface(bridge, JarvisBridge.JS_INTERFACE_NAME)

        binding.retryButton.setOnClickListener { loadGateway(force = true) }
        binding.settingsButton.setOnClickListener { openSettings() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        initTextToSpeech()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState != null) {
            // Process death only; a rotation does not reach here because the
            // Activity handles those configuration changes itself.
            binding.webView.restoreState(savedInstanceState)
            loadedUrl = savedInstanceState.getString(STATE_URL)
        }
        if (loadedUrl == null) loadGateway(force = false)

        handleShareIntent(intent)
    }

    /* ---------------- WebView plumbing ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() = with(binding.webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true              // the page keeps its transcript and token here
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false  // TTS/voice replies start without a tap
        // HTTPS-only: the gateway must be TLS-terminated so the page can open a
        // same-origin wss:// socket. See res/xml/network_security_config.xml.
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)
        loadWithOverviewMode = true
        useWideViewPort = true
        cacheMode = WebSettings.LOAD_DEFAULT
        allowFileAccess = false
        allowContentAccess = false
        // Geolocation stays off: the chat has no use for it, and leaving it
        // enabled would mean another consent prompt to justify.
        setGeolocationEnabled(false)
    }.also {
        WebView.setWebContentsDebuggingEnabled(com.jarvis.android.BuildConfig.DEBUG)
        binding.webView.webViewClient = JarvisWebViewClient()
        binding.webView.webChromeClient = JarvisWebChromeClient()
        binding.webView.setBackgroundColor(0xFF0B1220.toInt())
    }

    /**
     * The page registers a service worker (offline shell + asset caching). Without
     * a ServiceWorkerController the worker's own fetches are unhandled and the
     * registration silently misbehaves.
     */
    private fun configureServiceWorker() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        val controller = ServiceWorkerControllerCompat.getInstance()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS)) {
            controller.serviceWorkerWebSettings.allowContentAccess = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_FILE_ACCESS)) {
            controller.serviceWorkerWebSettings.allowFileAccess = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                // Returning null lets the worker's requests go to the network as usual.
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
            })
        }
    }

    private fun loadGateway(force: Boolean) {
        val url = settings.gatewayUrl
        if (!Settings.isValid(url)) {
            showError(getString(R.string.error_no_url))
            return
        }
        if (!force && loadedUrl == url) return
        loadedUrl = url
        showContent()
        binding.webView.loadUrl(url)
    }

    private inner class JarvisWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val target = request.url
            // Anything on the gateway's own origin is the app; everything else is
            // somebody's link and belongs in the user's browser.
            return if (isGatewayOrigin(target)) {
                false
            } else {
                openExternally(target)
                true
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            // Re-injected on every navigation: a page load wipes the previous
            // window.JarvisNative along with the rest of the JS context.
            view.evaluateJavascript(JarvisBridge.SHIM_JS, null)
            bridge.send(
                BridgeContract.Outbound.READY,
                payload = JSONObject()
                    .put("platform", "android")
                    .put("sdkInt", Build.VERSION.SDK_INT)
                    .put("appVersion", com.jarvis.android.BuildConfig.VERSION_NAME)
                    .put("sessionRunning", JarvisSessionService.isRunning())
            )
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            // Sub-resource failures are the page's problem, not the shell's.
            if (request.isForMainFrame) showError(getString(R.string.error_load))
        }
    }

    private inner class JarvisWebChromeClient : WebChromeClient() {

        /**
         * `getUserMedia` in the page lands here. The Android runtime permission has
         * to be held first, so an ungranted request is parked until the system
         * dialog comes back.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (!wantsAudio || !isGatewayOrigin(request.origin)) {
                request.deny()
                return
            }
            if (JarvisSessionService.hasMicPermission(this@MainActivity)) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                maybeStartSessionService()
            } else {
                pendingWebPermissionRequest = request
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingWebPermissionRequest == request) pendingWebPermissionRequest = null
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, false, false)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.progress = newProgress
            binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                fileChooserCallback = null
                false
            }
        }
    }

    private fun isGatewayOrigin(url: Uri?): Boolean {
        if (url == null) return false
        val gateway = Uri.parse(settings.gatewayUrl)
        return url.host != null &&
            url.host.equals(gateway.host, ignoreCase = true) &&
            url.port == gateway.port
    }

    private fun isGatewayOrigin(origin: String): Boolean = isGatewayOrigin(Uri.parse(origin))

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, uri.toString(), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorPanel.visibility = View.VISIBLE
        binding.webView.visibility = View.GONE
    }

    private fun showContent() {
        binding.errorPanel.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
    }

    /* ---------------- lifecycle ---------------- */

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        binding.webView.resumeTimers()
        loadGateway(force = false)
        bridge.send(
            BridgeContract.Outbound.LIFECYCLE,
            payload = JSONObject().put("state", "foreground")
        )
    }

    override fun onPause() {
        bridge.send(
            BridgeContract.Outbound.LIFECYCLE,
            payload = JSONObject().put("state", "background")
        )
        // Deliberately no webView.onPause()/pauseTimers() here: pausing JS timers
        // is exactly what would tear down the page's socket heartbeat while the
        // foreground service is trying to keep it alive.
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) maybeStartSessionService()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
        outState.putString(STATE_URL, loadedUrl)
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        if (isFinishing) JarvisSessionService.stop(this)
        binding.webView.removeJavascriptInterface(JarvisBridge.JS_INTERFACE_NAME)
        super.onDestroy()
    }

    private fun maybeStartSessionService() {
        if (!settings.keepSessionAlive) return
        if (!JarvisSessionService.hasMicPermission(this)) return
        JarvisSessionService.start(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /**
     * Text shared into Jarvis from another app arrives here and is handed to the
     * page as a `chat.inject` frame — the native -> web direction of the bridge
     * doing something a user can actually see.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        main.postDelayed({
            bridge.send(
                BridgeContract.Outbound.CHAT_INJECT,
                payload = JSONObject()
                    .put("role", "user")
                    .put("content", text)
                    .put("source", "android.share")
                    .put("autoSend", false)
            )
        }, CHAT_INJECT_DELAY_MS)
    }

    /* ---------------- bridge callbacks ---------------- */

    @SuppressLint("MissingPermission")
    override fun onNotify(title: String, body: String, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val intent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, JarvisApp.CHANNEL_BRIDGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this)
            .notify(tag, (tag?.hashCode() ?: body.hashCode()), notification)
    }

    override fun onSpeak(text: String, queue: Boolean, utteranceId: String?) {
        val id = utteranceId ?: text.hashCode().toString()
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeech.add(text to queue)
            return
        }
        val mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        engine.speak(text, mode, null, id)
    }

    override fun onStopSpeaking() {
        pendingSpeech.clear()
        tts?.stop()
    }

    override fun onShare(title: String?, text: String, url: String?) {
        val body = listOfNotNull(text.takeIf { it.isNotBlank() }, url).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(send, title))
    }

    override fun onHaptic(pattern: String) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()
        } ?: return

        val effect = when (pattern) {
            "tick" -> VibrationEffect.createOneShot(20, 60)
            "heavy" -> VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            else -> VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    override fun onSessionStart() {
        settings.keepSessionAlive = true
        if (!JarvisSessionService.hasMicPermission(this)) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        JarvisSessionService.start(this)
    }

    override fun onSessionStop() {
        JarvisSessionService.stop(this)
    }

    override fun isSessionRunning(): Boolean = JarvisSessionService.isRunning()

    override fun onOpenExternal(url: String) = openExternally(Uri.parse(url))

    /* ---------------- text to speech ---------------- */

    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech unavailable ($status)")
                return@TextToSpeech
            }
            tts?.language = Locale.getDefault()
            ttsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = notifyTtsState(utteranceId, "started")
                override fun onDone(utteranceId: String?) = notifyTtsState(utteranceId, "done")

                @Deprecated("Superseded by onError(String, int)")
                override fun onError(utteranceId: String?) = notifyTtsState(utteranceId, "error")
            })
            val queued = pendingSpeech.toList()
            pendingSpeech.clear()
            queued.forEach { (text, queue) -> onSpeak(text, queue, null) }
        }
    }

    private fun notifyTtsState(utteranceId: String?, state: String) {
        bridge.send(
            BridgeContract.Outbound.TTS_STATE,
            payload = JSONObject().put("state", state).put("utteranceId", utteranceId)
        )
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_URL = "loaded_url"

        /** Gives the page's own scripts a beat to install their bridge listeners. */
        private const val CHAT_INJECT_DELAY_MS = 1200L
    }
}
