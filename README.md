# Iva-Android — Jarvis Android shell

A single-Activity Kotlin app that wraps the
[Jarvis Chat Interface](https://github.com/aman-dhakar-191/Jarvis-Chat-Interface)
web frontend in a WebView, adds a native ↔ web message bridge, and keeps the
voice/WebSocket session alive in the background with a foreground service.

## Why the page is loaded, not bundled

The frontend is a PWA served by the Jarvis **gateway**, which is also the
WebSocket endpoint — the page and its `wss://` socket are same-origin. `app.js`
derives its socket address from `location.host` when no explicit gateway is
configured, passes the access token as a WebSocket subprotocol, registers a
service worker, and keeps its transcript in `localStorage`.

Bundling `gateway/public/` into `assets/` would put the page on a
`file://`-ish origin, break that same-origin inference, and break the service
worker. So the app loads the page **live from the gateway URL** and behaves as
much like a browser tab on that origin as it can.

## Configuring the gateway

Two ways, either is fine:

1. **In the app.** Launch it with no gateway set and it shows a setup screen;
   afterwards it is under **Settings**. The value is persisted in
   `SharedPreferences` and takes effect on the next load.
2. **At build time.** Bake in a default:

   ```bash
   ./gradlew assembleRelease -PjarvisGatewayUrl=https://jarvis.example.com
   ```

   This becomes `BuildConfig.DEFAULT_GATEWAY_URL`, used until the user changes
   it in Settings. In the release workflow, set the repository variable
   `JARVIS_GATEWAY_URL` to do the same for CI builds.

Enter the **https base URL** of the gateway (e.g. `https://jarvis.example.com`),
not a `wss://` URL — the page derives that itself. The access token still goes
in the page's own settings, exactly as in a browser.

### HTTPS only

`res/xml/network_security_config.xml` forbids cleartext traffic and
`MIXED_CONTENT_NEVER_ALLOW` is set, because a `wss://` socket needs a TLS
origin anyway. To point at a plaintext LAN gateway while developing, add that
host under a `<domain-config cleartextTrafficPermitted="true">` block in that
file and rebuild.

## What the shell does

**WebView** — JavaScript, DOM storage, database storage, media playback without
a user gesture, service workers wired through `ServiceWorkerControllerCompat`,
file chooser, back navigation mapped to WebView history, links off the gateway
origin handed to the system browser, WebView state retained across rotation
(the Activity handles those configuration changes itself), geolocation denied.

**Microphone** — `onPermissionRequest` handles `RESOURCE_AUDIO_CAPTURE` from the
page's `getUserMedia`. The Android `RECORD_AUDIO` runtime permission is
requested *first*; only once it is granted is the WebView-level request granted,
so capture never fails silently behind the page's back. Requests from any origin
other than the gateway are denied.

**Background session** — a `microphone`-typed foreground service with an ongoing
notification. There is no second WebSocket: the socket lives in the WebView, and
what kills such a session when the app is backgrounded is the *process* being
frozen or reclaimed, not the Activity stopping. Holding a foreground service
keeps the process out of the cached bucket, so the page's own socket, timers and
capture keep running. The Activity also deliberately does **not** call
`WebView.onPause()`/`pauseTimers()`, which would stop the page's heartbeat.

Android 13+ notification permission and Android 14+ foreground-service-type
rules are handled: the service refuses to start without `RECORD_AUDIO` (a
`microphone` service may not start without it on 14+), and Settings offers the
battery-optimisation exemption via the system dialog.

**Bridge** — a versioned JSON channel in both directions. See
**[BRIDGE.md](BRIDGE.md)** for the full message contract. Today it supports
web → native `notify`, `tts.speak`/`tts.stop`, `share`, `haptic`,
`session.start`/`stop`/`query`, `open.external`, and native → web `ready`,
`ack`/`pong`, `session.state`, `tts.state`, `chat.inject`, `lifecycle`.

## Building

Requires JDK 17 and the Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug        # debug APK
./gradlew lintDebug            # lint
./gradlew assembleRelease      # release APK (unsigned unless signing is configured)
./gradlew bundleRelease        # AAB
```

| | |
|---|---|
| minSdk | 26 (Android 8.0) |
| targetSdk / compileSdk | 35 (Android 15) |
| Language / build | Kotlin 2.0, Gradle Kotlin DSL, version catalog |

*Defaults chosen, note them if they don't suit you:* minSdk 26 because the
foreground-service and notification-channel model this app depends on starts
there; targetSdk 35 to get the current foreground-service-type rules right.

## Signing

Locally, create `keystore.properties` in the repository root (it is
`.gitignore`d):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=jarvis
keyPassword=…
```

Without it — and without the equivalent environment variables — the release
variant simply builds unsigned, so `assembleRelease` still works on a machine
with no secrets.

To create a keystore:

```bash
keytool -genkeypair -v -keystore release.jks -alias jarvis \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks > release.jks.base64   # macOS: base64 -i release.jks -o -
```

### Required GitHub secrets

Set these under **Settings → Secrets and variables → Actions → Secrets**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The `.jks` keystore, base64-encoded (single line, no wrapping). |
| `KEYSTORE_PASSWORD` | Keystore password. |
| `KEY_ALIAS` | Key alias inside the keystore (e.g. `jarvis`). |
| `KEY_PASSWORD` | Password for that key. |

Optional, under **Variables**:

| Variable | Value |
|---|---|
| `JARVIS_GATEWAY_URL` | Gateway URL baked into release builds. Leave unset to make the app ask on first run. |

## CI / release

- **`.github/workflows/ci.yml`** — on every push and pull request: `assembleDebug`,
  unit tests, `lintDebug`, and uploads the debug APK and lint report. Needs no
  secrets, so PRs from forks are checked normally.
- **`.github/workflows/release.yml`** — on a `v*` tag (or a published release, or
  manually with a tag): decodes `KEYSTORE_BASE64` into the runner temp
  directory, runs `assembleRelease` and `bundleRelease` with the signing
  environment, verifies the result with `apksigner verify --print-certs`,
  deletes the keystore, and attaches `jarvis-<tag>.apk` and `jarvis-<tag>.aab`
  to the GitHub release.

Cutting a release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## Layout

```
app/src/main/java/com/jarvis/android/
  JarvisApp.kt                  notification channels
  Settings.kt                   gateway URL + keep-alive preference
  bridge/BridgeContract.kt      message vocabulary and frame format
  bridge/JarvisBridge.kt        @JavascriptInterface transport + injected JS shim
  service/JarvisSessionService.kt   foreground service keeping the session alive
  ui/MainActivity.kt            the WebView host and bridge handlers
  ui/SettingsActivity.kt        gateway URL, keep-alive, battery exemption
```
