use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Path, Query, State,
    },
    http::{header, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::{
    sync::{atomic::Ordering, Arc},
    time::{Duration, Instant},
};
use tokio::sync::broadcast;

use crate::state::{SharedState, SttSession};
use crate::Language;

pub fn router(state: Arc<SharedState>) -> Router {
    Router::new()
        .route("/location", get(get_location))
        .route("/location/ws", get(ws_handler))
        .route("/stt/sessions", post(create_session).options(preflight))
        .route("/stt/sessions/{id}/audio", post(post_audio).options(preflight))
        .route("/stt/sessions/{id}/text", get(get_text))
        .route("/stt/sessions/{id}", delete(delete_session).options(preflight))
        .with_state(state)
}

pub fn spawn_stt_cleanup(state: Arc<SharedState>) {
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(10)).await;
            let now = Instant::now();
            let expired: Vec<Arc<SttSession>> = {
                let sessions = state.stt_sessions.read();
                sessions
                    .values()
                    .filter(|s| {
                        !s.ended.load(Ordering::SeqCst)
                            && now.duration_since(*s.last_active.lock()) > Duration::from_secs(60)
                    })
                    .cloned()
                    .collect()
            };
            for session in expired {
                remove_session(&state, &session.id);
            }
        }
    });
}

fn remove_session(state: &SharedState, id: &str) {
    let removed = state.stt_sessions.write().remove(id);
    if let Some(session) = removed {
        session.ended.store(true, Ordering::SeqCst);
        session.notify.notify_waiters();
        if let Some(streamer) = state.stt_streamers.read().get(&session.engine).cloned() {
            let id = id.to_owned();
            tokio::task::spawn_blocking(move || streamer.end_session(id));
        }
    }
}

fn cors() -> [(header::HeaderName, HeaderValue); 4] {
    [
        (header::ACCESS_CONTROL_ALLOW_ORIGIN, HeaderValue::from_static("*")),
        (header::ACCESS_CONTROL_ALLOW_METHODS, HeaderValue::from_static("GET, POST, DELETE, OPTIONS")),
        (header::ACCESS_CONTROL_ALLOW_HEADERS, HeaderValue::from_static("Content-Type")),
        (header::ACCESS_CONTROL_MAX_AGE, HeaderValue::from_static("86400")),
    ]
}

async fn preflight() -> Response {
    (cors(), StatusCode::NO_CONTENT).into_response()
}

async fn get_location(State(s): State<Arc<SharedState>>) -> Response {
    let provider = s.location_provider.read().clone();
    let Some(provider) = provider else {
        return (cors(), StatusCode::SERVICE_UNAVAILABLE).into_response();
    };
    let result = tokio::time::timeout(
        Duration::from_secs(10),
        tokio::task::spawn_blocking(move || provider.current()),
    )
    .await;
    match result {
        Ok(Ok(Some(loc))) => (cors(), Json(loc)).into_response(),
        _ => (cors(), StatusCode::SERVICE_UNAVAILABLE).into_response(),
    }
}

async fn ws_handler(ws: WebSocketUpgrade, State(s): State<Arc<SharedState>>) -> Response {
    ws.on_upgrade(move |socket| handle_socket(socket, s))
}

async fn handle_socket(mut socket: WebSocket, state: Arc<SharedState>) {
    let mut rx = state.location_tx.subscribe();

    let was_zero = state.subscriber_count.fetch_add(1, Ordering::SeqCst) == 0;
    if was_zero {
        if let Some(streamer) = state.location_streamer.read().clone() {
            streamer.start();
        }
    }

    loop {
        tokio::select! {
            msg = rx.recv() => match msg {
                Ok(loc) => {
                    let json = serde_json::to_string(&loc).unwrap_or_default();
                    if socket.send(Message::Text(json.into())).await.is_err() { break; }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            },
            client = socket.recv() => match client {
                None | Some(Err(_)) | Some(Ok(Message::Close(_))) => break,
                _ => {}
            },
        }
    }

    let was_last = state.subscriber_count.fetch_sub(1, Ordering::SeqCst) == 1;
    if was_last {
        if let Some(streamer) = state.location_streamer.read().clone() {
            streamer.stop();
        }
    }
}

// ── STT handlers ─────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct CreateSessionBody {
    language: String,
    engine: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CreateSessionResponse {
    session_id: String,
    language: String,
    engine: String,
    sample_rate: u32,
    encoding: &'static str,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TextQuery {
    since: Option<u64>,
    wait_ms: Option<u64>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct TranscriptItem {
    seq: u64,
    text: String,
    is_final: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TextResponse {
    session_id: String,
    transcripts: Vec<TranscriptItem>,
    next_since: u64,
}

async fn create_session(
    State(s): State<Arc<SharedState>>,
    Json(body): Json<CreateSessionBody>,
) -> Response {
    let (language, lang_str) = match body.language.as_str() {
        "ja" => (Language::Ja, "ja"),
        "en" => (Language::En, "en"),
        _ => return (cors(), StatusCode::BAD_REQUEST).into_response(),
    };

    let engine = body.engine.unwrap_or_else(|| "vosk".to_owned());

    // Clone out of the lock before spawn_blocking — RwLockReadGuard is !Send.
    let streamer = s.stt_streamers.read().get(&engine).cloned();
    let Some(streamer) = streamer else {
        return (
            cors(),
            (StatusCode::BAD_REQUEST, Json(serde_json::json!({"error": "unknown_engine", "engine": engine}))),
        )
            .into_response();
    };

    let ready = {
        let st = streamer.clone();
        tokio::task::spawn_blocking(move || st.is_language_ready(language))
            .await
            .unwrap_or(false)
    };
    if !ready {
        return (
            cors(),
            (StatusCode::SERVICE_UNAVAILABLE, Json(serde_json::json!({"error": "model_not_ready", "language": lang_str, "engine": engine}))),
        )
            .into_response();
    }

    let id = uuid::Uuid::new_v4().to_string();
    let session = Arc::new(SttSession {
        id: id.clone(),
        engine: engine.clone(),
        transcripts: parking_lot::Mutex::new(Vec::new()),
        notify: tokio::sync::Notify::new(),
        last_active: parking_lot::Mutex::new(Instant::now()),
        next_seq: std::sync::atomic::AtomicU64::new(0),
        ended: std::sync::atomic::AtomicBool::new(false),
    });
    s.stt_sessions.write().insert(id.clone(), session);

    let sid = id.clone();
    let _ = tokio::task::spawn_blocking(move || streamer.start_session(sid, language)).await;

    (
        cors(),
        Json(CreateSessionResponse {
            session_id: id,
            language: lang_str.to_owned(),
            engine,
            sample_rate: 16000,
            encoding: "pcm_s16le_mono",
        }),
    )
        .into_response()
}

async fn post_audio(
    Path(id): Path<String>,
    State(s): State<Arc<SharedState>>,
    body: axum::body::Bytes,
) -> Response {
    let session = s.stt_sessions.read().get(&id).cloned();
    let Some(session) = session else {
        return (cors(), StatusCode::NOT_FOUND).into_response();
    };
    if session.ended.load(Ordering::SeqCst) {
        return (cors(), StatusCode::GONE).into_response();
    }

    *session.last_active.lock() = Instant::now();

    if let Some(streamer) = s.stt_streamers.read().get(&session.engine).cloned() {
        let pcm = body.to_vec();
        let sid = id.clone();
        tokio::task::spawn_blocking(move || streamer.feed_audio(sid, pcm));
    }

    (cors(), StatusCode::NO_CONTENT).into_response()
}

async fn get_text(
    Path(id): Path<String>,
    Query(params): Query<TextQuery>,
    State(s): State<Arc<SharedState>>,
) -> Response {
    let session = s.stt_sessions.read().get(&id).cloned();
    let Some(session) = session else {
        return (cors(), StatusCode::NOT_FOUND).into_response();
    };
    if session.ended.load(Ordering::SeqCst) {
        return (cors(), StatusCode::GONE).into_response();
    }

    *session.last_active.lock() = Instant::now();

    let since = params.since.unwrap_or(0);
    let wait_ms = params.wait_ms.unwrap_or(25000).min(30000);

    // Register for notification before snapshotting to avoid the TOCTOU race
    // where push_transcript fires between our empty-check and select!.
    let notification = session.notify.notified();
    tokio::pin!(notification);
    notification.as_mut().enable();

    let snapshot = transcripts_since(&session, since);
    if !snapshot.is_empty() {
        let next_since = snapshot.last().map(|t| t.seq).unwrap_or(since);
        return (
            cors(),
            Json(TextResponse {
                session_id: id,
                transcripts: snapshot,
                next_since,
            }),
        )
            .into_response();
    }

    tokio::select! {
        _ = &mut notification => {}
        _ = tokio::time::sleep(Duration::from_millis(wait_ms)) => {}
    }

    if session.ended.load(Ordering::SeqCst) {
        return (cors(), StatusCode::GONE).into_response();
    }

    let snapshot = transcripts_since(&session, since);
    let next_since = snapshot.last().map(|t| t.seq).unwrap_or(since);
    (
        cors(),
        Json(TextResponse {
            session_id: id,
            transcripts: snapshot,
            next_since,
        }),
    )
        .into_response()
}

async fn delete_session(Path(id): Path<String>, State(s): State<Arc<SharedState>>) -> Response {
    remove_session(&s, &id);
    (cors(), StatusCode::NO_CONTENT).into_response()
}

fn transcripts_since(session: &SttSession, since: u64) -> Vec<TranscriptItem> {
    session
        .transcripts
        .lock()
        .iter()
        .filter(|t| t.seq > since)
        .map(|t| TranscriptItem {
            seq: t.seq,
            text: t.text.clone(),
            is_final: t.is_final,
        })
        .collect()
}
