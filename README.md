# Even Companion

Android companion app for [Even Realities G2](https://www.evenrealities.com/) smart glasses.

Exposes device APIs over a localhost HTTP server so Even G2 web apps can `fetch()` them — working around the Even Realities App WebView's lack of location permission, built-in STT's cloud dependency, and the absence of on-device translation/summarization.

## How it works

The Even G2 runs web apps inside a WebView that cannot request location permission, and its built-in STT requires a cloud connection. Even Companion runs as a foreground service on the paired Android phone and serves GPS, on-device speech-to-text, and on-device LLM (translation / summarization) on `http://127.0.0.1:44423`.

```
G2 WebApp  ──fetch()──▶  Even Companion (Android)
                              │
            ┌─────────────────┼──────────────────┐
            │                 │                  │
   FusedLocationProvider   STT engines     Gemma 4 E2B LLM
            │            ┌───┴──────────┐  (via LiteRT-LM)
      GPS hardware        │             │
                       VOSK        Sherpa-ONNX    translate
                   (JA + EN)     streaming ZIP    summarize
                                   (EN only)
```

## API

All endpoints accept and return JSON (`Content-Type: application/json`). CORS headers are included on every response.

### Location

#### `GET /location`

Returns the current GPS fix as JSON.

```json
{
  "latitude": 35.6812,
  "longitude": 139.7671,
  "altitude": 40.0,
  "accuracyM": 5.0,
  "bearingDeg": 270.0,
  "speedMps": 1.2,
  "speedAccuracyMps": 0.8,
  "timestampMs": 1746749400000
}
```

`altitude`, `accuracyM`, `bearingDeg`, `speedMps`, and `speedAccuracyMps` are omitted when not available.

Returns `503 Service Unavailable` if no fix is available within 10 seconds.

#### `GET /location/ws`

WebSocket stream of location updates (~1 Hz). Each message is a JSON object in the same format as above.

> **Note:** The Even Hub WebView suspends WebSocket connections when backgrounded, making this endpoint effectively unusable in practice. Use `GET /location` with 1-second polling instead.

### Speech-to-Text

Fully on-device STT with two engine options. Audio is supplied by the G2 glasses mic through the Even Hub bridge API (`bridge.audioControl(true)` / `onEvenHubEvent` → `audioEvent.audioPcm`). Format: 16 kHz, 16-bit little-endian mono (10 ms / 40 bytes per frame from the bridge).

| Engine | Languages | Model type | Notes |
|--------|-----------|------------|-------|
| `vosk` (default) | JA, EN | [VOSK](https://alphacephei.com/vosk/) small | ~49 MB JA / ~41 MB EN |
| `sherpa` | EN only | [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) streaming Zipformer | higher accuracy; requires AAR setup (see [Setup](#setup)) |

Before using STT, download at least one language model from the companion app's main screen.

#### `POST /stt/sessions`

Create a new STT session.

Request body:
```json
{ "language": "ja", "engine": "vosk" }
```

`language` is `"ja"` (Japanese) or `"en"` (English).  
`engine` is `"vosk"` (default) or `"sherpa"`. Omitting `engine` defaults to `"vosk"`.

Response `200`:
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "language": "en",
  "engine": "sherpa",
  "sampleRate": 16000,
  "encoding": "pcm_s16le_mono"
}
```

Response `400` for an unknown engine:
```json
{ "error": "unknown_engine", "engine": "foo" }
```

Response `503` if the model for the requested language/engine has not been downloaded:
```json
{ "error": "model_not_ready", "language": "en", "engine": "sherpa" }
```

#### `POST /stt/sessions/{id}/audio`

Push raw PCM audio. Body is raw 16-bit little-endian mono PCM at 16 kHz. Any chunk size is accepted; batching ~100–250 ms (1600–4000 bytes) per POST is recommended to reduce HTTP overhead.

Returns `204 No Content` on success, `404` if session unknown, `410` if session ended.

#### `GET /stt/sessions/{id}/text?since=N&waitMs=25000`

Long-poll for transcripts. Returns when new transcripts with `seq > since` are available, or after `waitMs` (max 30 000 ms). On timeout, returns an empty transcript list — re-poll immediately.

Response `200`:
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "transcripts": [
    { "seq": 1, "text": "おはよう", "isFinal": false },
    { "seq": 2, "text": "おはようございます", "isFinal": true }
  ],
  "nextSince": 2
}
```

`isFinal: false` = partial result (may change); `isFinal: true` = committed utterance.

#### `DELETE /stt/sessions/{id}`

End a session and free its resources. Returns `204`.

Sessions auto-expire after 60 seconds of inactivity (no audio POST and no active long-poll).

### LLM (translation / summarization)

On-device inference via [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) running through [LiteRT-LM SDK](https://github.com/google-ai-edge/LiteRT-LM). The model (~2.6 GB) is downloaded in-app and stored in app-private storage — no AICore or system dependency.

**Requirements:** 3 GB+ RAM recommended. GPU backend (Adreno 7xx / Mali-G715+) gives best performance (~500 ms–2 s per request); falls back to CPU on unsupported hardware. The companion app's main screen shows the model status and a **Download** button.

Both endpoints return `503` with `{"error": "model_not_ready"}` until the Gemma model is downloaded.

#### `POST /llm/translate`

Translate text between Japanese and English.

Request body:
```json
{ "text": "おはようございます", "from": "ja", "to": "en" }
```

`from` and `to` are `"ja"` or `"en"`.

Response `200`:
```json
{ "text": "Good morning." }
```

| Status | Body |
|--------|------|
| `200` | `{ "text": "…" }` |
| `400` | `{ "error": "bad_request", "reason": "…" }` — empty text or unsupported language code |
| `503` | `{ "error": "model_not_ready" }` — model not available yet |
| `500` | `{ "error": "inference_failed", "reason": "…" }` |

#### `POST /llm/summarize`

Summarize text. The optional `language` field hints at the desired output language; if omitted the model decides.

Request body:
```json
{ "text": "…長い文章…", "language": "ja" }
```

`language` is `"ja"` or `"en"` (optional).

Response `200`:
```json
{ "text": "要約された内容" }
```

Same error responses as `/llm/translate`.

> **Limits:** Very long input texts may be truncated at ~4 000 tokens (~3 000 words). Output length is determined by the model's default generation settings.

## Setup

### Requirements

- Android Studio (for NDK and JDK)
- Android NDK installed via Android Studio → Preferences → Android SDK → SDK Tools → NDK (Side by side)
- Rust with Android targets:

```sh
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

#### Sherpa-ONNX AAR (required for the `sherpa` engine)

The sherpa-onnx prebuilt AAR is not included in this repository. Download it once and place it in `app/libs/`:

```sh
curl -L -o app/libs/sherpa-onnx-1.13.1.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.1/sherpa-onnx-1.13.1.aar
```

If you skip this step the app still builds and the `vosk` engine works; the `sherpa` engine will fail at runtime with an `unknown_engine` error.

### Build & install

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug

~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Running

1. Open the app on your Android phone
2. Grant location permission when prompted
3. Download STT models — tap **Download** in the VOSK or Sherpa-ONNX section for each language you need
4. Download the Gemma model — tap **Download** in the Gemma section (~2.6 GB, stored in app-private storage)
5. Tap **Start** — the foreground notification confirms the server is running
6. Query `http://127.0.0.1:44423/location`, `/stt/*`, or `/llm/*` endpoints from your G2 web app

The server binds to `127.0.0.1` only in release builds. Debug builds bind to `0.0.0.0` for easier testing from a computer on the same network.

## Architecture

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose (Kotlin) |
| Core | Rust, bridged via [UniFFI](https://github.com/mozilla/uniffi-rs) |
| HTTP server | tokio + axum |
| GPS | FusedLocationProviderClient (Google Play Services) |
| STT | [VOSK](https://alphacephei.com/vosk/) + [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) (on-device, streaming) |
| LLM | [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) via [LiteRT-LM SDK](https://github.com/google-ai-edge/LiteRT-LM) (on-device, in-process) |

The Rust core owns the HTTP server lifecycle. Kotlin implements UniFFI foreign traits for GPS, STT, and LLM:

- `LocationProvider` — called on-demand by `GET /location`
- `LocationStreamer` — started/stopped by `WS /location/ws` as clients connect and disconnect
- `SttStreamer` — interface implemented by both `VoskSttStreamer` and `SherpaOnnxSttStreamer`; the engine is chosen at session creation time and stored on the session
- `LlmEngine` — interface implemented by `GemmaLlmEngine`; prompts are constructed in Rust, Kotlin calls LiteRT-LM for inference

STT models are downloaded on demand (no asset bundling). VOSK models go to `filesDir/vosk/`, Sherpa-ONNX models to `filesDir/sherpa/`. The Gemma 4 E2B model is stored in `filesDir/gemma/` (app-private, no AICore dependency).

The server runs inside a foreground service (`type=location`) so it stays alive when the app is backgrounded or the screen is locked.

### Repository layout

```
even-companion/
├── app/                    Android app module
│   └── src/main/java/dev/typester/evencompanion/
│       ├── core/           EvenCore singleton
│       ├── llm/            GemmaLlmEngine (LiteRT-LM)
│       ├── location/       PollingLocationProvider, FusedLocationStreamer
│       ├── service/        CoreService (foreground service)
│       ├── stt/            VoskSttStreamer, VoskModelManager, SherpaOnnxSttStreamer, SherpaModelManager
│       └── ui/             Jetpack Compose screens
└── rust/
    ├── core/               cdylib → libevencore.so  (UniFFI + HTTP server)
    └── uniffi-bindgen/     host binary for Kotlin binding generation
```

## Default port

`44423` — hardcoded in the Even G2 web app side. Configurable in the companion app Settings (stored in SharedPreferences). Port conflicts surface as an error in the UI.
