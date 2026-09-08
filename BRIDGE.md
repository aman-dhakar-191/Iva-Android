# Jarvis native bridge

A generic, versioned JSON message channel between the Jarvis web page and the
Android shell that hosts it.

**Status: scaffolding.** The current frontend
([Jarvis-Chat-Interface](https://github.com/aman-dhakar-191/Jarvis-Chat-Interface))
exposes `window.Jarvis` — its internal seam between the chat and the voice layer —
but does not call any native bridge. Everything described here is injected by
the app on every page load so the web side can adopt it incrementally. In a
plain browser `window.JarvisNative.available` is `false` and nothing breaks.

## Wire format

Every frame, in both directions, is one JSON object:

```json
{ "v": 1, "id": "w17", "type": "notify", "payload": { "body": "…" } }
```

| Field     | Meaning |
|-----------|---------|
| `v`       | Contract version. Bumped only for breaking changes; a receiver rejects frames whose `v` it does not know rather than guessing. |
| `id`      | Correlation id. Optional. When present, the reply (`ack` or `pong`) echoes it. |
| `type`    | Message name, from the tables below. |
| `payload` | Type-specific object. Always present, possibly empty. |

The transport is deliberately one generic entry point rather than a method per
feature: adding a capability on the web side never requires shipping a new APK
to a page that already knows how to send JSON.

## Web → native

Send with `window.JarvisNative.send(type, payload)`, which returns a Promise
resolving with the `ack` payload (or rejecting with its `error`). The promise
rejects with `bridge timeout` after 10s.

| `type` | payload | Effect |
|---|---|---|
| `ping` | — | Replies `pong`. Use it to confirm the channel is live. |
| `notify` | `{ title?, body, tag? }` | Posts an Android notification on the "Jarvis alerts" channel. Tapping it opens the app. `tag` replaces a previous notification with the same tag. No-op (acked) if the user denied `POST_NOTIFICATIONS`. |
| `tts.speak` | `{ text, queue? }` | Speaks `text` with the Android TTS engine. `queue: true` appends, otherwise it interrupts. Progress arrives as `tts.state` frames carrying the request's `id` as `utteranceId`. |
| `tts.stop` | — | Stops speaking and drops anything queued. |
| `share` | `{ text, title?, url? }` | Opens the Android share sheet with `text` (and `url` appended on its own line). |
| `haptic` | `{ pattern }` | Vibrates. `pattern` is `tick`, `click` (default) or `heavy`. |
| `session.start` | — | Turns on the background-session foreground service. Requests the microphone permission first if it is not held. |
| `session.stop` | — | Stops the foreground service. |
| `session.query` | — | Acks and then emits the current `session.state`. |
| `open.external` | `{ url }` | Opens the URL in the user's browser instead of the WebView. |

Rejected requests come back as an `ack` with `{ ok: false, error }`: unknown
type, unsupported version, or a missing required field.

## Native → web

Subscribe with `window.JarvisNative.on(type, fn)` (returns an unsubscribe
function). Every frame is *also* dispatched on `window` as a
`jarvis-native` `CustomEvent` whose `detail` is the whole frame.

| `type` | payload | When |
|---|---|---|
| `ready` | `{ platform, sdkInt, appVersion, sessionRunning }` | After each page load, once the shim is installed. |
| `ack` | `{ ok, error? }` | Reply to a web → native request; carries its `id`. |
| `pong` | — | Reply to `ping`; carries its `id`. |
| `session.state` | `{ running }` | The foreground service started or stopped. |
| `tts.state` | `{ state, utteranceId }` | `started`, `done` or `error` for a `tts.speak`. |
| `chat.inject` | `{ role, content, source, autoSend }` | Native has text for the chat — today, text shared into Jarvis from another Android app (`source: "android.share"`). `autoSend: false` means "put it in the composer", not "send it". |
| `lifecycle` | `{ state }` | `foreground` or `background`, as the Activity resumes/pauses. |

## Adopting it from the page

The shim is installed before `ready` fires, so the safe pattern is:

```js
window.addEventListener('jarvis-native-ready', () => {
  if (!window.JarvisNative.available) return; // plain browser, nothing to do

  // native → web: drop shared text into the composer
  window.JarvisNative.on('chat.inject', ({ content, autoSend }) => {
    composer.value = content;
    if (autoSend) send();
  });

  // web → native: speak assistant replies out loud
  window.Jarvis.onVoiceEvent(evt => {
    if (evt.type === 'assistant.text') {
      window.JarvisNative.send('tts.speak', { text: evt.text }).catch(console.warn);
    }
  });

  // web → native: notify when a reply lands while the app is backgrounded
  let backgrounded = false;
  window.JarvisNative.on('lifecycle', ({ state }) => { backgrounded = state === 'background'; });
  onAssistantMessage(msg => {
    if (backgrounded) window.JarvisNative.send('notify', { title: 'Jarvis', body: msg.summary });
  });
});
```

If your code may run after the shim is already installed, check
`window.JarvisNative` first and fall back to the event listener.

## Implementation notes

- Native side: `app/src/main/java/com/jarvis/android/bridge/`
  (`BridgeContract.kt` for the vocabulary, `JarvisBridge.kt` for the transport
  and the injected shim), with the handlers implemented in `ui/MainActivity.kt`.
- `AndroidJarvis.postMessage()` is called on a WebView JS thread and returns
  only a receipt (`{accepted, …}`); the real result arrives asynchronously as
  `ack`. Handlers run on the main thread.
- Only the gateway origin is ever loaded in the WebView, so the bridge is not
  exposed to third-party pages. Keep it that way if you add navigation.
- ProGuard keeps `@JavascriptInterface` members (`app/proguard-rules.pro`);
  they have no visible call sites for the shrinker.

## Versioning

Additive changes (a new `type`, a new optional payload field) keep `v: 1`.
Renaming or removing a `type`, or changing a payload field's meaning, bumps
`BridgeContract.VERSION` — and older apps then reject the frames outright
instead of misinterpreting them.
