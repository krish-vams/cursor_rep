//! Per-subgraph HTTP client with timeout and structured error handling.
//!
//! Each subgraph call:
//!   * Wraps the JSON request body shaped like `{query, variables, operationName}`.
//!   * Applies the per-subgraph timeout from the registry config.
//!   * Returns a [`SubgraphCallOutcome`] capturing one of:
//!       - `Success` with the parsed `{data, errors}` payload + duration
//!       - `Timeout` when `tokio::time::timeout` fires
//!       - `Transport` for connection failures and 5xx responses
//!
//! The executor in `executor.rs` (Phase 3.4) consumes this outcome and decides how it maps
//! onto the merged `{data, errors}` body returned to the client.

use crate::config::SubgraphConfig;
use crate::error::GraphQLError;
use serde::Serialize;
use std::time::{Duration, Instant};

#[derive(Debug, Serialize)]
pub struct SubgraphRequestBody<'a> {
    pub query: &'a str,
    #[serde(skip_serializing_if = "serde_json::Value::is_null")]
    pub variables: serde_json::Value,
    #[serde(rename = "operationName", skip_serializing_if = "Option::is_none")]
    pub operation_name: Option<&'a str>,
}

/// What happened when we called a subgraph.
#[derive(Debug)]
pub enum SubgraphCallOutcome {
    Success {
        subgraph: String,
        duration: Duration,
        data: serde_json::Value,
        errors: Vec<GraphQLError>,
    },
    Timeout {
        subgraph: String,
        elapsed: Duration,
        timeout: Duration,
    },
    Transport {
        subgraph: String,
        duration: Duration,
        message: String,
        status: Option<u16>,
    },
}

impl SubgraphCallOutcome {
    pub fn subgraph(&self) -> &str {
        match self {
            SubgraphCallOutcome::Success { subgraph, .. }
            | SubgraphCallOutcome::Timeout { subgraph, .. }
            | SubgraphCallOutcome::Transport { subgraph, .. } => subgraph,
        }
    }

    pub fn duration(&self) -> Duration {
        match self {
            SubgraphCallOutcome::Success { duration, .. } => *duration,
            SubgraphCallOutcome::Timeout { elapsed, .. } => *elapsed,
            SubgraphCallOutcome::Transport { duration, .. } => *duration,
        }
    }
}

#[derive(Clone)]
pub struct SubgraphClient {
    http: reqwest::Client,
}

impl SubgraphClient {
    pub fn new() -> anyhow::Result<Self> {
        let http = reqwest::Client::builder()
            .pool_max_idle_per_host(32)
            .tcp_keepalive(Duration::from_secs(60))
            .user_agent(concat!("travelgraph-router/", env!("CARGO_PKG_VERSION")))
            .build()?;
        Ok(Self { http })
    }

    /// Call a subgraph with a per-request timeout sourced from the registry.
    pub async fn call(
        &self,
        cfg: &SubgraphConfig,
        body: SubgraphRequestBody<'_>,
        request_id: &str,
    ) -> SubgraphCallOutcome {
        let start = Instant::now();
        let request = self
            .http
            .post(&cfg.url)
            .header("content-type", "application/json")
            .header("x-request-id", request_id)
            .json(&body);

        let attempt = tokio::time::timeout(cfg.timeout, request.send());
        let resp = match attempt.await {
            Err(_) => {
                return SubgraphCallOutcome::Timeout {
                    subgraph: cfg.name.clone(),
                    elapsed: start.elapsed(),
                    timeout: cfg.timeout,
                };
            }
            Ok(Err(e)) => {
                return SubgraphCallOutcome::Transport {
                    subgraph: cfg.name.clone(),
                    duration: start.elapsed(),
                    message: format!("transport error: {}", e),
                    status: None,
                };
            }
            Ok(Ok(resp)) => resp,
        };

        let status = resp.status();
        if !status.is_success() {
            let body_text = resp.text().await.unwrap_or_default();
            return SubgraphCallOutcome::Transport {
                subgraph: cfg.name.clone(),
                duration: start.elapsed(),
                message: format!("subgraph returned {}: {}", status, truncate(&body_text, 256)),
                status: Some(status.as_u16()),
            };
        }

        // Parse the JSON body. A 200 with malformed JSON is treated as transport.
        let parsed: serde_json::Value = match resp.json().await {
            Ok(v) => v,
            Err(e) => {
                return SubgraphCallOutcome::Transport {
                    subgraph: cfg.name.clone(),
                    duration: start.elapsed(),
                    message: format!("malformed JSON in response: {}", e),
                    status: Some(status.as_u16()),
                };
            }
        };

        let data = parsed.get("data").cloned().unwrap_or(serde_json::Value::Null);
        let errors: Vec<GraphQLError> = parsed
            .get("errors")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|e| serde_json::from_value(e.clone()).ok())
                    .collect()
            })
            .unwrap_or_default();

        SubgraphCallOutcome::Success {
            subgraph: cfg.name.clone(),
            duration: start.elapsed(),
            data,
            errors,
        }
    }
}

fn truncate(s: &str, max: usize) -> &str {
    if s.len() <= max { s } else { &s[..max] }
}

// Allow GraphQLError to be deserialised when it appears in subgraph responses.
// (The Serialize impl lives in error.rs; this is the matching Deserialize.)
mod gql_error_deser {
    use super::GraphQLError;
    use serde::Deserialize;

    #[derive(Deserialize)]
    struct RawError {
        #[serde(default)]
        message: String,
        #[serde(default)]
        locations: Vec<RawLocation>,
        #[serde(default)]
        path: serde_json::Value,
        #[serde(default)]
        extensions: Option<serde_json::Value>,
    }

    #[derive(Deserialize)]
    struct RawLocation { line: u32, column: u32 }

    impl<'de> Deserialize<'de> for GraphQLError {
        fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
            let raw = RawError::deserialize(d)?;
            let mut err = GraphQLError::new(raw.message);
            for loc in raw.locations {
                err = err.with_location(loc.line, loc.column);
            }
            if let Some(arr) = raw.path.as_array() {
                let mut path = Vec::with_capacity(arr.len());
                for seg in arr {
                    if let Some(s) = seg.as_str() {
                        path.push(crate::error::PathSegment::Key(s.to_string()));
                    } else if let Some(i) = seg.as_u64() {
                        path.push(crate::error::PathSegment::Index(i as usize));
                    }
                }
                err = err.with_path(path);
            }
            if let Some(ext) = raw.extensions {
                err.extensions = Some(ext);
            }
            Ok(err)
        }
    }
}
