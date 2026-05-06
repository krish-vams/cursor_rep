//! Tracing setup and request-scoped logging.
//!
//! For each `/graphql` request we:
//!   * Generate a `request_id` (UUIDv4) and stash it on the request span.
//!   * Time the entire request and each subgraph call.
//!   * Emit a single structured `request_completed` log line with:
//!       request_id, operation_name, total_duration_ms, status, error_count,
//!       per-subgraph durations.
//!
//! In Phase 6 these signals will additionally feed the OpenTelemetry collector.

use std::collections::BTreeMap;
use std::time::Duration;

pub fn init_tracing() {
    use tracing_subscriber::EnvFilter;
    use tracing_subscriber::fmt;
    use tracing_subscriber::prelude::*;

    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));

    // JSON logs in production, human-friendly in dev (toggle via ROUTER_LOG_FORMAT).
    let json_logs = std::env::var("ROUTER_LOG_FORMAT").as_deref() == Ok("json");
    let registry = tracing_subscriber::registry().with(filter);
    if json_logs {
        registry.with(fmt::layer().json().with_target(true).with_current_span(true)).init();
    } else {
        registry.with(fmt::layer().with_target(true)).init();
    }
}

/// Snapshot of one completed request -- used to emit the final structured log line.
#[derive(Debug)]
pub struct RequestTrace {
    pub request_id: String,
    pub operation_name: Option<String>,
    pub total_duration: Duration,
    pub error_count: usize,
    pub status: RequestStatus,
    pub subgraph_durations: BTreeMap<String, Duration>,
}

#[derive(Debug, Clone, Copy)]
pub enum RequestStatus {
    Ok,
    PartialErrors,
    ParseFailed,
    ValidationFailed,
}

impl RequestStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            RequestStatus::Ok => "ok",
            RequestStatus::PartialErrors => "partial_errors",
            RequestStatus::ParseFailed => "parse_failed",
            RequestStatus::ValidationFailed => "validation_failed",
        }
    }
}

impl RequestTrace {
    pub fn emit(&self) {
        // serde_json keeps a stable per-key ordering for the durations map.
        let durations_ms: BTreeMap<&str, u128> = self
            .subgraph_durations
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_millis()))
            .collect();
        tracing::info!(
            request_id = %self.request_id,
            operation_name = %self.operation_name.as_deref().unwrap_or("(unnamed)"),
            total_duration_ms = self.total_duration.as_millis() as u64,
            status = self.status.as_str(),
            error_count = self.error_count,
            subgraph_durations_ms = ?durations_ms,
            "request_completed"
        );
    }
}
