use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    http::{header, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use std::{
    sync::{atomic::Ordering, Arc},
    time::Duration,
};
use tokio::sync::broadcast;

use crate::state::SharedState;

pub fn router(state: Arc<SharedState>) -> Router {
    Router::new()
        .route("/location", get(get_location))
        .route("/location/ws", get(ws_handler))
        .with_state(state)
}

async fn get_location(State(s): State<Arc<SharedState>>) -> Response {
    // Wildcard is safe: server binds to loopback only in release builds.
    let cors = [(header::ACCESS_CONTROL_ALLOW_ORIGIN, HeaderValue::from_static("*"))];
    let provider = s.location_provider.read().clone();
    let Some(provider) = provider else {
        return (cors, StatusCode::SERVICE_UNAVAILABLE).into_response();
    };
    let result = tokio::time::timeout(
        Duration::from_secs(10),
        tokio::task::spawn_blocking(move || provider.current()),
    )
    .await;
    match result {
        Ok(Ok(Some(loc))) => (cors, Json(loc)).into_response(),
        _ => (cors, StatusCode::SERVICE_UNAVAILABLE).into_response(),
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
