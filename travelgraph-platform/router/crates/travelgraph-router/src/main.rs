//! TravelGraph Platform — Rust GraphQL router.
//!
//! Phase 3 scope (current):
//!   * Axum HTTP server with `/graphql` (POST) and `/health` (GET).
//!   * Apollo-parser based parse + structural validation.
//!   * **Federated** query planning sourced from the composed supergraph SDL
//!     (Phase 3.2 composer output) -- replaces the Phase 2 hand-rolled gateway.
//!   * Batched `_entities` entity-resolution round-trips (Phase 3.4): one HTTP
//!     call per extending subgraph per request, regardless of fan-out width.
//!   * Per-subgraph timeouts and request-scoped tracing (incl. per-subgraph durations).
//!
//! Out of scope until later phases:
//!   * Reliability layer: retries, circuit breakers, backpressure (Phase 4)
//!   * JWT auth, persisted query lookup, depth/cost limits        (Phase 5)
//!   * OpenTelemetry export to Prometheus / Jaeger                (Phase 6)

use std::net::SocketAddr;
use std::sync::Arc;

use axum::routing::{get, post};
use axum::Router;
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::trace::TraceLayer;

mod config;
mod error;
mod executor;
mod handler;
mod observability;
mod parser;
mod planner;
mod subgraph;
mod supergraph;

use config::SubgraphRegistry;
use handler::{graphql, health, AppState};
use subgraph::SubgraphClient;
use supergraph::SupergraphSchema;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    observability::init_tracing();

    // ---- Configuration ------------------------------------------------------
    let config_path = std::env::var("ROUTER_SUBGRAPH_CONFIG")
        .unwrap_or_else(|_| "config/subgraphs.yaml".to_string());
    let mut registry = SubgraphRegistry::from_yaml_file(&config_path)?;

    // ---- Supergraph schema --------------------------------------------------
    // The supergraph SDL is produced by the schema-registry composer (Phase 3.2). The
    // router reads it at startup; if it's missing we fail to boot rather than silently
    // falling back to Phase 2 routing.
    let supergraph_path = std::env::var("ROUTER_SUPERGRAPH_PATH")
        .unwrap_or_else(|_| "config/supergraph.graphql".to_string());
    let supergraph_sdl = std::fs::read_to_string(&supergraph_path).map_err(|e| {
        anyhow::anyhow!(
            "failed to read supergraph SDL from {}: {} \n\
             Run `make compose` (or `gradle :schema-registry:composer:run`) to regenerate it.",
            supergraph_path,
            e
        )
    })?;
    let supergraph = SupergraphSchema::parse(&supergraph_sdl).map_err(|e| {
        anyhow::anyhow!(
            "supergraph SDL at {} is not parseable: {}",
            supergraph_path,
            e.message
        )
    })?;
    registry.merge_supergraph_hints(&supergraph.subgraphs);

    tracing::info!(
        subgraph_config = %config_path,
        supergraph_path = %supergraph_path,
        subgraphs = ?registry.subgraphs.keys().collect::<Vec<_>>(),
        types = supergraph.types.len(),
        "router boot: subgraph registry + supergraph schema loaded"
    );

    let client = SubgraphClient::new()?;

    let state = AppState {
        registry: Arc::new(registry),
        supergraph: Arc::new(supergraph),
        client: Arc::new(client),
    };

    // ---- Routing ------------------------------------------------------------
    // 256 KiB request-body cap; persisted queries (Phase 5) means real prod traffic
    // is tiny anyway. TraceLayer adds per-request HTTP-level tracing.
    let app = Router::new()
        .route("/health", get(health))
        .route("/graphql", post(graphql))
        .layer(RequestBodyLimitLayer::new(256 * 1024))
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    // ---- Bind ---------------------------------------------------------------
    let port: u16 = std::env::var("ROUTER_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(8080);
    let addr: SocketAddr = SocketAddr::from(([0, 0, 0, 0], port));

    tracing::info!(%addr, "router listening");
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}
