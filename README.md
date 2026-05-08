# Even Companion

Android companion app for [Even Realities G2](https://www.evenrealities.com/) smart glasses.

Exposes device APIs over a localhost HTTP server so Even G2 web apps can `fetch()` them — working around the Even Realities App WebView's lack of location permission.

## How it works

The Even G2 runs web apps inside a WebView that cannot request location permission. Even Companion runs as a foreground service on the paired Android phone, subscribes to GPS, and serves the data on `http://127.0.0.1:44423`. Your G2 web app fetches from that address.

```
G2 WebApp  ──fetch()──▶  Even Companion (Android)
                              │
                    FusedLocationProviderClient
                              │
                           GPS hardware
```

## API

### `GET /location`

Returns the current GPS fix as JSON.

```json
{
  "latitude": 35.6812,
  "longitude": 139.7671,
  "altitude": 40.0,
  "accuracyM": 5.0,
  "bearingDeg": 270.0,
  "speedMps": 1.2,
  "timestampMs": 1746749400000
}
```

`altitude`, `accuracyM`, `bearingDeg`, and `speedMps` are omitted when not available.

Returns `503 Service Unavailable` if no fix is available within 10 seconds.

The GPS subscription starts on the first request and stays active while requests keep coming. It shuts down automatically after 60 seconds of inactivity.

### `GET /location/ws`

WebSocket stream of location updates (~1 Hz). Each message is a JSON object in the same format as above.

The GPS subscription starts when the first client connects and stops when the last client disconnects.

## Setup

### Requirements

- Android Studio (for NDK and JDK)
- Android NDK installed via Android Studio → Preferences → Android SDK → SDK Tools → NDK (Side by side)
- Rust with Android targets:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
```

### Build & install

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Running

1. Open the app on your Android phone
2. Grant location permission when prompted
3. Tap **Start** — the foreground notification confirms the server is running
4. Query `http://127.0.0.1:44423/location` from your G2 web app

The server binds to `127.0.0.1` only in release builds. Debug builds bind to `0.0.0.0` for easier testing from a computer on the same network.

## Architecture

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose (Kotlin) |
| Core | Rust, bridged via [UniFFI](https://github.com/mozilla/uniffi-rs) |
| HTTP server | tokio + axum |
| GPS | FusedLocationProviderClient (Google Play Services) |

The Rust core owns the HTTP server lifecycle. Kotlin implements two UniFFI foreign traits:

- `LocationProvider` — called on-demand by `GET /location`; maintains a persistent GPS subscription that idles out after 60 s of no requests
- `LocationStreamer` — started/stopped by `WS /location/ws` as clients connect and disconnect

The server runs inside a foreground service (`type=location`) so it stays alive when the app is backgrounded or the screen is locked.

### Repository layout

```
even-companion/
├── app/                    Android app module
│   └── src/main/java/dev/typester/evencompanion/
│       ├── core/           EvenCore singleton
│       ├── location/       PollingLocationProvider, FusedLocationStreamer
│       ├── service/        CoreService (foreground service)
│       └── ui/             Jetpack Compose screens
└── rust/
    ├── core/               cdylib → libevencore.so  (UniFFI + HTTP server)
    └── uniffi-bindgen/     host binary for Kotlin binding generation
```

## Default port

`44423` — hardcoded in the Even G2 web app side. Configurable in the companion app Settings (stored in SharedPreferences). Port conflicts surface as an error in the UI.
