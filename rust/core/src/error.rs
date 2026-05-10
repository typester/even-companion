#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum LlmError {
    #[error("model is not ready")]
    NotReady,
    #[error("inference failed: {0}")]
    Inference(String),
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("server is already running on port {0}")]
    AlreadyRunning(u16),
    #[error("server is not running")]
    NotRunning,
    #[error("port {0} is already in use")]
    AddressInUse(u16),
    #[error("failed to bind: {0}")]
    Bind(String),
    #[error("runtime error: {0}")]
    Runtime(String),
}
