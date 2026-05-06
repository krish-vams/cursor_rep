//! Axum HTTP handlers for `/graphql` and `/health`.
//!
//! Pipeline (Phase 3):
//!   1. Generate a request_id and start the timing span.
//!   2. Parse + validate the document (`parser::parse_and_validate`).
//!   3. Resolve the operation by name (`parser::resolve_operation`).
//!   4. Build a federated execution plan from the supergraph schema (`planner::plan`).
//!   5. Execute the plan (`executor::execute`) -- this is where the `_entities`
//!      round-trips happen, batched per-subgraph per-request.
//!   6. Emit the request log line and respond with 200 + `{data, errors}`.
//!
//! Status codes:
//!   * Parse failure  -> 400
//!   * Validation     -> 200, errors in body (per GraphQL spec)
//!   * Anything below -> 200, errors in body

use crate::config::SubgraphRegistry;
use crate::error::{GraphQLRequest, GraphQLResponse};
use crate::executor;
use crate::observability::{RequestStatus, RequestTrace};
use crate::parser::{self, ParseOutcome};
use crate::planner::{self, PlanOutcome};
use crate::subgraph::SubgraphClient;
use crate::supergraph::SupergraphSchema;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use std::sync::Arc;
use std::time::Instant;
use uuid::Uuid;

#[derive(Clone)]
pub struct AppState {
    pub registry: Arc<SubgraphRegistry>,
    pub supergraph: Arc<SupergraphSchema>,
    pub client: Arc<SubgraphClient>,
}

pub async fn health() -> impl IntoResponse {
    (StatusCode::OK, "ok")
}

pub async fn graphql(
    State(state): State<AppState>,
    Json(req): Json<GraphQLRequest>,
) -> impl IntoResponse {
    let started = Instant::now();
    let request_id = Uuid::new_v4().to_string();

    // ---- 1. Parse + structural validation ------------------------------
    let parsed = match parser::parse_and_validate(&req.query) {
        ParseOutcome::Ok(p) => p,
        ParseOutcome::ParseErrors(errs) => {
            RequestTrace {
                request_id: request_id.clone(),
                operation_name: req.operation_name.clone(),
                total_duration: started.elapsed(),
                error_count: errs.len(),
                status: RequestStatus::ParseFailed,
                subgraph_durations: Default::default(),
            }
            .emit();
            return (StatusCode::BAD_REQUEST, Json(GraphQLResponse::errors_only(errs)))
                .into_response();
        }
        ParseOutcome::ValidationErrors(errs) => {
            RequestTrace {
                request_id: request_id.clone(),
                operation_name: req.operation_name.clone(),
                total_duration: started.elapsed(),
                error_count: errs.len(),
                status: RequestStatus::ValidationFailed,
                subgraph_durations: Default::default(),
            }
            .emit();
            return (StatusCode::OK, Json(GraphQLResponse::errors_only(errs))).into_response();
        }
    };

    // ---- 2. Resolve operation ------------------------------------------
    let op = match parser::resolve_operation(&parsed, req.operation_name.as_deref()) {
        Ok(o) => o,
        Err(err) => {
            RequestTrace {
                request_id: request_id.clone(),
                operation_name: req.operation_name.clone(),
                total_duration: started.elapsed(),
                error_count: 1,
                status: RequestStatus::ValidationFailed,
                subgraph_durations: Default::default(),
            }
            .emit();
            return (StatusCode::OK, Json(GraphQLResponse::errors_only(vec![err]))).into_response();
        }
    };

    // ---- 3. Plan ---------------------------------------------------------
    let plan = match planner::plan(&parsed, op, &state.supergraph) {
        PlanOutcome::Plan(node) => node,
        PlanOutcome::Errors(errs) => {
            RequestTrace {
                request_id: request_id.clone(),
                operation_name: op.name.clone(),
                total_duration: started.elapsed(),
                error_count: errs.len(),
                status: RequestStatus::ValidationFailed,
                subgraph_durations: Default::default(),
            }
            .emit();
            return (StatusCode::OK, Json(GraphQLResponse::errors_only(errs))).into_response();
        }
    };

    tracing::debug!(
        request_id = %request_id,
        plan = ?plan,
        "execution plan"
    );

    // ---- 4. Execute -----------------------------------------------------
    let out = executor::execute(plan, &state.registry, &state.client, &req.variables, &request_id)
        .await;

    let status = if out.errors.is_empty() {
        RequestStatus::Ok
    } else {
        RequestStatus::PartialErrors
    };
    RequestTrace {
        request_id: request_id.clone(),
        operation_name: op.name.clone(),
        total_duration: started.elapsed(),
        error_count: out.errors.len(),
        status,
        subgraph_durations: out.subgraph_durations,
    }
    .emit();

    let response = GraphQLResponse {
        data: out.data,
        errors: out.errors,
        extensions: Some(serde_json::json!({ "requestId": request_id })),
    };
    (StatusCode::OK, Json(response)).into_response()
}
