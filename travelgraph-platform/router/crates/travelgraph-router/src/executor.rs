//! Plan executor — Phase 3.4.
//!
//! Walks the [`PlanNode`] tree produced by the planner and produces the merged
//! `{ data, errors }` body. The two interesting pieces:
//!
//!   1. **RootFetch parallel dispatch**: at each `Parallel` node we spawn one task per
//!      child via `tokio::join!`-equivalent. Each child either resolves a `RootFetch` (HTTP
//!      POST to the owning subgraph) or an `EntityFetch` (`_entities` call to an extending
//!      subgraph using representations harvested from previously-merged data).
//!
//!   2. **Batched entity round-trips**: every `EntityFetch` collects ALL entity references
//!      under its `from_response_key` into a single `representations: [_Any!]!` list, so
//!      regardless of how many properties a `searchProperties` query returns, the router
//!      makes EXACTLY ONE call per extending subgraph per request. This is the N+1 fix.
//!
//! The executor logs each step at debug level. Set `RUST_LOG=travelgraph_router=debug` to
//! see the per-request execution-plan trace required by Phase 3.3 acceptance.

use crate::config::SubgraphRegistry;
use crate::error::{GraphQLError, PathSegment};
use crate::planner::{EntityFetch, EntityOutput, PlanNode, RootFetch};
use crate::subgraph::{SubgraphCallOutcome, SubgraphClient, SubgraphRequestBody};
use futures::stream::{FuturesUnordered, StreamExt};
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;

/// Output of executing a plan -- the same shape as Phase 2's `MergedResponse`.
pub struct ExecutorOutput {
    pub data: Value,
    pub errors: Vec<GraphQLError>,
    pub subgraph_durations: BTreeMap<String, Duration>,
}

/// Shared state threaded through plan execution. Wrapped in an `Arc<Mutex<...>>` so parallel
/// branches can mutate the same `data`/`errors` accumulators without `&mut` aliasing issues.
struct State {
    data: Map<String, Value>,
    errors: Vec<GraphQLError>,
    subgraph_durations: BTreeMap<String, Duration>,
    /// For each top-level response key that returned an entity (or list of entities),
    /// the entity type's metadata so EntityFetches can cross-reference what was fetched.
    /// Currently only populated for diagnostic logging; the executor itself reads each
    /// field directly off the [`EntityFetch`] node it is processing.
    #[allow(dead_code)]
    entity_outputs: BTreeMap<String, EntityOutput>,
}

pub async fn execute(
    plan: PlanNode,
    registry: &SubgraphRegistry,
    client: &SubgraphClient,
    variables: &Value,
    request_id: &str,
) -> ExecutorOutput {
    let state = Arc::new(Mutex::new(State {
        data: Map::new(),
        errors: Vec::new(),
        subgraph_durations: BTreeMap::new(),
        entity_outputs: BTreeMap::new(),
    }));

    execute_node(&plan, registry, client, variables, request_id, &state).await;

    let s = Arc::try_unwrap(state)
        .ok()
        .expect("execution state should be uniquely owned at end of plan")
        .into_inner();
    ExecutorOutput {
        data: Value::Object(s.data),
        errors: s.errors,
        subgraph_durations: s.subgraph_durations,
    }
}

fn execute_node<'a>(
    node: &'a PlanNode,
    registry: &'a SubgraphRegistry,
    client: &'a SubgraphClient,
    variables: &'a Value,
    request_id: &'a str,
    state: &'a Arc<Mutex<State>>,
) -> futures::future::BoxFuture<'a, ()> {
    Box::pin(async move {
        match node {
            PlanNode::Sequence(children) => {
                for child in children {
                    execute_node(child, registry, client, variables, request_id, state).await;
                }
            }
            PlanNode::Parallel(children) => {
                let mut futures = FuturesUnordered::new();
                for child in children {
                    futures.push(execute_node(
                        child, registry, client, variables, request_id, state,
                    ));
                }
                while futures.next().await.is_some() {}
            }
            PlanNode::RootFetch(rf) => {
                tracing::debug!(
                    subgraph = %rf.subgraph,
                    operation_name = ?rf.operation_name,
                    response_keys = ?rf.response_keys,
                    "execute root_fetch"
                );
                run_root_fetch(rf, registry, client, variables, request_id, state).await;
            }
            PlanNode::EntityFetch(ef) => {
                tracing::debug!(
                    subgraph = %ef.subgraph,
                    entity_type = %ef.entity_type,
                    response_key = %ef.from_response_key,
                    contributed_fields = ?ef.contributed_fields,
                    "execute entity_fetch"
                );
                run_entity_fetch(ef, registry, client, variables, request_id, state).await;
            }
        }
    })
}

async fn run_root_fetch(
    rf: &RootFetch,
    registry: &SubgraphRegistry,
    client: &SubgraphClient,
    variables: &Value,
    request_id: &str,
    state: &Arc<Mutex<State>>,
) {
    let cfg = match registry.get(&rf.subgraph) {
        Some(c) => c.clone(),
        None => {
            let err = GraphQLError::new(format!(
                "Plan references unknown subgraph `{}`. Re-run the supergraph composer.",
                rf.subgraph
            ))
            .with_code("UNKNOWN_SUBGRAPH");
            let mut s = state.lock().await;
            s.errors.push(err);
            for key in &rf.response_keys {
                s.data.insert(key.clone(), Value::Null);
            }
            return;
        }
    };

    let body = SubgraphRequestBody {
        query: &rf.query,
        variables: variables.clone(),
        operation_name: rf.operation_name.as_deref(),
    };
    let outcome = client.call(&cfg, body, request_id).await;
    let mut s = state.lock().await;
    s.subgraph_durations
        .insert(outcome.subgraph().to_string(), outcome.duration());

    match outcome {
        SubgraphCallOutcome::Success { data, errors, .. } => {
            // Adopt every top-level key in `data` that this fetch claims responsibility for.
            if let Value::Object(map) = data {
                for key in &rf.response_keys {
                    if let Some(v) = map.get(key) {
                        s.data.insert(key.clone(), v.clone());
                    } else {
                        // Subgraph returned data without this key -- treat as null.
                        s.data.insert(key.clone(), Value::Null);
                    }
                }
            } else {
                for key in &rf.response_keys {
                    s.data.insert(key.clone(), Value::Null);
                }
            }
            for e in errors {
                s.errors.push(e);
            }
            // Record entity outputs for any subsequent EntityFetch step.
            for eo in &rf.entity_outputs {
                s.entity_outputs.insert(eo.response_key.clone(), eo.clone());
            }
        }
        SubgraphCallOutcome::Timeout { subgraph, timeout, .. } => {
            for key in &rf.response_keys {
                s.data.insert(key.clone(), Value::Null);
                s.errors.push(
                    GraphQLError::new(format!(
                        "subgraph `{}` did not respond within {} ms",
                        subgraph,
                        timeout.as_millis()
                    ))
                    .with_path(vec![PathSegment::Key(key.clone())])
                    .with_code("UPSTREAM_TIMEOUT")
                    .with_extension("subgraph", json!(subgraph))
                    .with_extension("timeoutMs", json!(timeout.as_millis() as u64)),
                );
            }
        }
        SubgraphCallOutcome::Transport { subgraph, message, status, .. } => {
            for key in &rf.response_keys {
                s.data.insert(key.clone(), Value::Null);
                let mut err = GraphQLError::new(format!(
                    "subgraph `{}` returned a transport error: {}",
                    subgraph, message
                ))
                .with_path(vec![PathSegment::Key(key.clone())])
                .with_code("UPSTREAM_UNAVAILABLE")
                .with_extension("subgraph", json!(subgraph));
                if let Some(s) = status {
                    err = err.with_extension("upstreamStatus", json!(s));
                }
                s.errors.push(err);
            }
        }
    }
}

async fn run_entity_fetch(
    ef: &EntityFetch,
    registry: &SubgraphRegistry,
    client: &SubgraphClient,
    variables: &Value,
    request_id: &str,
    state: &Arc<Mutex<State>>,
) {
    // Harvest representations from the data we have so far. Lock briefly to read; we'll
    // re-lock to merge the response.
    let representations: Vec<Value>;
    let target_path: TargetPath;
    {
        let s = state.lock().await;
        let host = match s.data.get(&ef.from_response_key) {
            Some(v) => v.clone(),
            None => {
                tracing::warn!(
                    subgraph = %ef.subgraph,
                    response_key = %ef.from_response_key,
                    "entity fetch skipped: no data at response key"
                );
                return;
            }
        };
        target_path = match &host {
            Value::Array(_) => TargetPath::List,
            Value::Object(_) => TargetPath::Single,
            _ => return,
        };
        representations = collect_representations(&host, &ef.entity_type, &ef.key_field);
    }

    if representations.is_empty() {
        tracing::debug!(
            subgraph = %ef.subgraph,
            "entity fetch skipped: no representations to send"
        );
        return;
    }

    let cfg = match registry.get(&ef.subgraph) {
        Some(c) => c.clone(),
        None => {
            let mut s = state.lock().await;
            s.errors.push(
                GraphQLError::new(format!(
                    "Plan references unknown subgraph `{}` for entity fetch.",
                    ef.subgraph
                ))
                .with_code("UNKNOWN_SUBGRAPH"),
            );
            return;
        }
    };

    // Compose variables: keep any user-supplied variables, add representations.
    let mut entity_vars = match variables {
        Value::Object(m) => m.clone(),
        _ => Map::new(),
    };
    entity_vars.insert(
        "representations".to_string(),
        Value::Array(representations.clone()),
    );
    let entity_vars = Value::Object(entity_vars);

    let body = SubgraphRequestBody {
        query: &ef.query,
        variables: entity_vars,
        operation_name: None,
    };
    let outcome = client.call(&cfg, body, request_id).await;

    let mut s = state.lock().await;
    let prev = s
        .subgraph_durations
        .get(outcome.subgraph())
        .cloned()
        .unwrap_or_default();
    s.subgraph_durations.insert(
        outcome.subgraph().to_string(),
        prev + outcome.duration(),
    );

    match outcome {
        SubgraphCallOutcome::Success { data, errors, .. } => {
            for e in errors {
                s.errors.push(e);
            }
            let entities_array = data
                .get("_entities")
                .and_then(|v| v.as_array())
                .cloned()
                .unwrap_or_default();
            merge_entities_into_data(
                &mut s.data,
                &ef.from_response_key,
                target_path,
                &entities_array,
                &ef.contributed_fields,
            );
        }
        SubgraphCallOutcome::Timeout { subgraph, timeout, .. } => {
            for f in &ef.contributed_fields {
                s.errors.push(
                    GraphQLError::new(format!(
                        "extending subgraph `{}` did not respond within {} ms while resolving `{}`",
                        subgraph, timeout.as_millis(), f
                    ))
                    .with_code("UPSTREAM_TIMEOUT")
                    .with_extension("subgraph", json!(subgraph))
                    .with_extension("timeoutMs", json!(timeout.as_millis() as u64)),
                );
            }
            null_out_extending_fields(
                &mut s.data,
                &ef.from_response_key,
                target_path,
                &ef.contributed_fields,
            );
        }
        SubgraphCallOutcome::Transport { subgraph, message, status, .. } => {
            let mut err = GraphQLError::new(format!(
                "extending subgraph `{}` returned a transport error: {}",
                subgraph, message
            ))
            .with_code("UPSTREAM_UNAVAILABLE")
            .with_extension("subgraph", json!(subgraph));
            if let Some(st) = status {
                err = err.with_extension("upstreamStatus", json!(st));
            }
            s.errors.push(err);
            null_out_extending_fields(
                &mut s.data,
                &ef.from_response_key,
                target_path,
                &ef.contributed_fields,
            );
        }
    }
}

#[derive(Copy, Clone)]
enum TargetPath {
    Single,
    List,
}

fn collect_representations(host: &Value, entity_type: &str, key_field: &str) -> Vec<Value> {
    let mut out = Vec::new();
    match host {
        Value::Array(items) => {
            for item in items {
                if let Some(rep) = build_representation(item, entity_type, key_field) {
                    out.push(rep);
                }
            }
        }
        Value::Object(_) => {
            if let Some(rep) = build_representation(host, entity_type, key_field) {
                out.push(rep);
            }
        }
        _ => {}
    }
    out
}

fn build_representation(item: &Value, entity_type: &str, key_field: &str) -> Option<Value> {
    let key_value = item.get(key_field)?.clone();
    if key_value.is_null() {
        return None;
    }
    Some(json!({
        "__typename": entity_type,
        key_field: key_value,
    }))
}

/// Merge entities returned from `_entities` back into the in-memory data. The order of the
/// returned entities matches the order of the representations we sent, so we rely on
/// positional alignment.
fn merge_entities_into_data(
    data: &mut Map<String, Value>,
    response_key: &str,
    target: TargetPath,
    entities: &[Value],
    contributed_fields: &[String],
) {
    let host = match data.get_mut(response_key) {
        Some(v) => v,
        None => return,
    };
    match target {
        TargetPath::List => {
            if let Value::Array(items) = host {
                let mut entity_iter = entities.iter();
                for item in items.iter_mut() {
                    if !item.is_object() {
                        continue;
                    }
                    let entity = match entity_iter.next() {
                        Some(e) => e,
                        None => break,
                    };
                    if let (Value::Object(target_obj), Value::Object(source_obj)) = (item, entity) {
                        for f in contributed_fields {
                            if let Some(v) = source_obj.get(f) {
                                target_obj.insert(f.clone(), v.clone());
                            }
                        }
                    }
                }
            }
        }
        TargetPath::Single => {
            if let (Value::Object(target_obj), Some(Value::Object(source_obj))) =
                (host, entities.first())
            {
                for f in contributed_fields {
                    if let Some(v) = source_obj.get(f) {
                        target_obj.insert(f.clone(), v.clone());
                    }
                }
            }
        }
    }
}

fn null_out_extending_fields(
    data: &mut Map<String, Value>,
    response_key: &str,
    target: TargetPath,
    fields: &[String],
) {
    let host = match data.get_mut(response_key) {
        Some(v) => v,
        None => return,
    };
    match target {
        TargetPath::List => {
            if let Value::Array(items) = host {
                for item in items.iter_mut() {
                    if let Value::Object(obj) = item {
                        for f in fields {
                            obj.insert(f.clone(), Value::Null);
                        }
                    }
                }
            }
        }
        TargetPath::Single => {
            if let Value::Object(obj) = host {
                for f in fields {
                    obj.insert(f.clone(), Value::Null);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn collects_representations_from_a_list() {
        let host = json!([
            {"__typename":"Property","id":"a","name":"X"},
            {"__typename":"Property","id":"b","name":"Y"},
        ]);
        let reps = collect_representations(&host, "Property", "id");
        assert_eq!(reps.len(), 2);
        assert_eq!(reps[0]["__typename"], "Property");
        assert_eq!(reps[0]["id"], "a");
        assert_eq!(reps[1]["id"], "b");
    }

    #[test]
    fn merges_entities_into_data_by_position() {
        let mut data = Map::new();
        data.insert(
            "searchProperties".to_string(),
            json!([
                {"id":"a","name":"X"},
                {"id":"b","name":"Y"},
            ]),
        );
        let entities = vec![
            json!({"price":{"totalAmount":100.0}}),
            json!({"price":{"totalAmount":200.0}}),
        ];
        merge_entities_into_data(
            &mut data,
            "searchProperties",
            TargetPath::List,
            &entities,
            &["price".to_string()],
        );
        let arr = data["searchProperties"].as_array().unwrap();
        assert_eq!(arr[0]["price"]["totalAmount"], 100.0);
        assert_eq!(arr[1]["price"]["totalAmount"], 200.0);
        // Pre-existing fields preserved.
        assert_eq!(arr[0]["name"], "X");
    }

    #[test]
    fn nulls_out_extending_fields_on_failure() {
        let mut data = Map::new();
        data.insert(
            "searchProperties".to_string(),
            json!([{"id":"a","name":"X"}]),
        );
        null_out_extending_fields(
            &mut data,
            "searchProperties",
            TargetPath::List,
            &["price".to_string(), "reviews".to_string()],
        );
        let arr = data["searchProperties"].as_array().unwrap();
        assert!(arr[0]["price"].is_null());
        assert!(arr[0]["reviews"].is_null());
    }
}
