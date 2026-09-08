package com.jarvis.android.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Generic, versioned message-passing channel between the page and the shell.
 *
 * NOTE ON THE WEB SIDE: as of today the Jarvis frontend exposes `window.Jarvis`
 * (its internal voice/socket seam) but does not call any native bridge at all.
 * Everything here is therefore scaffolding: it is injected on every page load so
 * that the web app can adopt it incrementally, and it degrades to a no-op in a
 * plain browser where `window.AndroidJarvis` is undefined. Nothing in the page
 * breaks if it never adopts it.
 *
 * Web -> native goes through the single [postMessage] entry point below rather
 * than a method per feature, so adding a capability never means shipping a new
 * app build to a page that already knows how to send JSON.
 *
 * Native -> web goes through `evaluateJavascript`, calling into the small shim
 * this class injects as `window.JarvisNative`.
 */
class JarvisBridge(
    private val webView: WebView,
    private val handler: Handler,
    private val callbacks: Callbacks
) {

    /**
     * Implemented by the hosting Activity. Every method is invoked on the main
     * thread; implementations must return quickly and reply asynchronously via
     * [send] where the work is long-running.
     */
    interface Callbacks {
        fun onNotify(title: String, body: String, tag: String?)
        fun onSpeak(text: String, queue: Boolean, utteranceId: String?)
        fun onStopSpeaking()
        fun onShare(title: String?, text: String, url: String?)
        fun onHaptic(pattern: String)
        fun onSessionStart()
        fun onSessionStop()
        fun isSessionRunning(): Boolean
        fun onOpenExternal(url: String)
    }

    /**
     * Called from a WebView-owned JavaScript thread, never the main thread.
     *
     * The value returned is a JSON envelope acknowledging receipt only — the
     * result of the request itself arrives later as an `ack` frame carrying the
     * same `id`.
     */
    @JavascriptInterface
    fun postMessage(raw: String): String {
        val message = BridgeMessage.parse(raw)
            ?: return JSONObject().put("accepted", false).put("error", "malformed").toString()

        if (message.version != BridgeContract.VERSION) {
            return JSONObject()
                .put("accepted", false)
                .put("error", "unsupported version ${message.version}")
                .toString()
        }

        handler.post { dispatch(message) }
        return JSONObject().put("accepted", true).put("v", BridgeContract.VERSION).toString()
    }

    /** Contract version, so the page can feature-detect before sending anything. */
    @JavascriptInterface
    fun version(): Int = BridgeContract.VERSION

    private fun dispatch(message: BridgeMessage) {
        val p = message.payload
        try {
            when (message.type) {
                BridgeContract.Inbound.PING ->
                    send(BridgeContract.Outbound.PONG, message.id)

                BridgeContract.Inbound.NOTIFY -> {
                    val body = p.optString("body")
                    if (body.isBlank()) return fail(message.id, "notify requires a body")
                    callbacks.onNotify(
                        title = p.optString("title").ifBlank { "Jarvis" },
                        body = body,
                        tag = p.optString("tag").takeIf { it.isNotBlank() }
                    )
                    ack(message.id)
                }

                BridgeContract.Inbound.TTS_SPEAK -> {
                    val text = p.optString("text")
                    if (text.isBlank()) return fail(message.id, "tts.speak requires text")
                    callbacks.onSpeak(
                        text = text,
                        queue = p.optBoolean("queue", false),
                        utteranceId = message.id
                    )
                    ack(message.id)
                }

                BridgeContract.Inbound.TTS_STOP -> {
                    callbacks.onStopSpeaking()
                    ack(message.id)
                }

                BridgeContract.Inbound.SHARE -> {
                    val text = p.optString("text")
                    if (text.isBlank()) return fail(message.id, "share requires text")
                    callbacks.onShare(
                        title = p.optString("title").takeIf { it.isNotBlank() },
                        text = text,
                        url = p.optString("url").takeIf { it.isNotBlank() }
                    )
                    ack(message.id)
                }

                BridgeContract.Inbound.HAPTIC -> {
                    callbacks.onHaptic(p.optString("pattern").ifBlank { "click" })
                    ack(message.id)
                }

                BridgeContract.Inbound.SESSION_START -> {
                    callbacks.onSessionStart()
                    ack(message.id)
                    sendSessionState()
                }

                BridgeContract.Inbound.SESSION_STOP -> {
                    callbacks.onSessionStop()
                    ack(message.id)
                    sendSessionState()
                }

                BridgeContract.Inbound.SESSION_QUERY -> {
                    ack(message.id)
                    sendSessionState()
                }

                BridgeContract.Inbound.OPEN_EXTERNAL -> {
                    val url = p.optString("url")
                    if (url.isBlank()) return fail(message.id, "open.external requires a url")
                    callbacks.onOpenExternal(url)
                    ack(message.id)
                }

                else -> fail(message.id, "unknown type ${message.type}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "bridge handler failed for ${message.type}", e)
            fail(message.id, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun ack(id: String?) =
        send(BridgeContract.Outbound.ACK, id, JSONObject().put("ok", true))

    private fun fail(id: String?, error: String) =
        send(BridgeContract.Outbound.ACK, id, JSONObject().put("ok", false).put("error", error))

    fun sendSessionState() = send(
        BridgeContract.Outbound.SESSION_STATE,
        payload = JSONObject().put("running", callbacks.isSessionRunning())
    )

    /** Native -> web. Safe to call from any thread. */
    fun send(type: String, id: String? = null, payload: JSONObject = JSONObject()) {
        val frame = bridgeFrame(type, id, payload).toString()
        val script = "window.JarvisNative && window.JarvisNative._receive($frame);"
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evaluate(script)
        } else {
            handler.post { evaluate(script) }
        }
    }

    private fun evaluate(script: String) {
        try {
            webView.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Log.w(TAG, "evaluateJavascript failed", e)
        }
    }

    companion object {
        /** The name the shim is bound to inside the page. */
        const val JS_INTERFACE_NAME = "AndroidJarvis"
        private const val TAG = "JarvisBridge"

        /**
         * The page-side half of the channel. Injected after every page load so
         * the frontend can rely on it without shipping any Android-specific
         * code of its own; in a normal browser `window.AndroidJarvis` is absent
         * and `JarvisNative.available` is simply false.
         */
        val SHIM_JS: String = """
        (function () {
          if (window.JarvisNative && window.JarvisNative.version === ${BridgeContract.VERSION}) return;
          var native = window.$JS_INTERFACE_NAME;
          var listeners = {};
          var pending = {};
          var seq = 0;
          var api = {
            version: ${BridgeContract.VERSION},
            available: !!native,
            /* Fire-and-forget, or await the matching ack: JarvisNative.send('notify', {...}) */
            send: function (type, payload) {
              if (!native) return Promise.reject(new Error('native bridge unavailable'));
              var id = 'w' + (++seq);
              var frame = { v: ${BridgeContract.VERSION}, id: id, type: type, payload: payload || {} };
              var result = JSON.parse(native.postMessage(JSON.stringify(frame)));
              if (!result.accepted) return Promise.reject(new Error(result.error || 'rejected'));
              return new Promise(function (resolve, reject) {
                pending[id] = { resolve: resolve, reject: reject };
                setTimeout(function () {
                  if (pending[id]) { delete pending[id]; reject(new Error('bridge timeout')); }
                }, 10000);
              });
            },
            /* JarvisNative.on('chat.inject', function (payload) { ... }) */
            on: function (type, fn) {
              (listeners[type] = listeners[type] || []).push(fn);
              return function () {
                listeners[type] = (listeners[type] || []).filter(function (f) { return f !== fn; });
              };
            },
            _receive: function (frame) {
              if (!frame || frame.v !== ${BridgeContract.VERSION}) return;
              var waiter = frame.id ? pending[frame.id] : null;
              if (waiter && (frame.type === 'ack' || frame.type === 'pong')) {
                delete pending[frame.id];
                if (frame.type === 'pong' || frame.payload.ok) waiter.resolve(frame.payload);
                else waiter.reject(new Error(frame.payload.error || 'failed'));
              }
              (listeners[frame.type] || []).forEach(function (fn) {
                try { fn(frame.payload, frame); } catch (e) { console.error(e); }
              });
              window.dispatchEvent(new CustomEvent('jarvis-native', { detail: frame }));
            }
          };
          window.JarvisNative = api;
          window.dispatchEvent(new Event('jarvis-native-ready'));
        })();
        """.trimIndent()
    }
}
