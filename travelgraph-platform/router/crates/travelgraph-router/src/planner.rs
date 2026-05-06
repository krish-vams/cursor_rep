//! Federated query planner — Phase 3.3.
//!
//! Replaces the Phase 2 hand-rolled `routing` module. Given a parsed query and a
//! [`SupergraphSchema`], produces an [`ExecutionPlan`] tree consisting of:
//!
//!   * **RootFetch** nodes -- the initial parallel calls to subgraphs, one per owning subgraph
//!     for the top-level query/mutation fields.
//!   * **EntityFetch** nodes -- the `_entities(representations: [...])` round-trips that resolve
//!     fields contributed by *extending* subgraphs on entity types appearing in a root fetch's
//!     response. We batch one EntityFetch per `(parent_path, target_subgraph)` pair so an
//!     `_entities` call covering N entities is one HTTP roundtrip, not N (Phase 3.4).
//!
//! The plan tree is `Sequence([Parallel(root_fetches), Parallel(entity_fetches)])`. We keep it
//! that simple on purpose: the acceptance criteria for Phase 3 are bounded to one level of
//! entity extension. Multi-level chains (entity -> entity -> different subgraph) will need a
//! deeper planner; we defer that until Phase 4 / 5 where it becomes a real concern.
//!
//! Important: this planner *rewrites the selection set sent to each subgraph*. The naive
//! "slice the original source" approach used in Phase 2 would send fields the target
//! subgraph does not own, which would now fail schema validation at the subgraph. So for each
//! root fetch we emit a fresh sub-query containing only that subgraph's fields, plus
//! `__typename` and the entity key (so the executor can build representations for the
//! entity-fetch step).

use crate::error::GraphQLError;
use crate::parser::{OperationKind, OperationView, ParsedQuery};
use crate::supergraph::SupergraphSchema;
use apollo_parser::cst::{self, CstNode};
use apollo_parser::SyntaxTree;
use std::collections::BTreeMap;

#[derive(Debug, Clone)]
pub enum PlanNode {
    Sequence(Vec<PlanNode>),
    Parallel(Vec<PlanNode>),
    RootFetch(RootFetch),
    EntityFetch(EntityFetch),
}

/// One initial subgraph call at the root of the plan. Multiple RootFetches in one plan are
/// run in parallel.
#[derive(Debug, Clone)]
pub struct RootFetch {
    pub subgraph: String,
    /// Operation kind for diagnostics; the executor only ever issues queries today since the
    /// underlying HTTP body is keyed off `query` directly.
    #[allow(dead_code)]
    pub operation_kind: OperationKind,
    pub operation_name: Option<String>,
    /// Variable declarations text reused across the root fetch's sub-query. Captured here so
    /// downstream tracing can render the original signature even after the planner rewrites
    /// the body.
    #[allow(dead_code)]
    pub variable_definitions_text: String,
    pub query: String,
    /// Top-level response keys this fetch provides. The merger uses these to attach the
    /// fetch's `data` into the merged response object.
    pub response_keys: Vec<String>,
    /// For each top-level field that returns an entity type, the entity type name and key
    /// fields. Populated only for fields whose return type is a known entity in the
    /// supergraph -- the executor uses this to harvest representations for child entity
    /// fetches.
    pub entity_outputs: Vec<EntityOutput>,
}

#[derive(Debug, Clone)]
pub struct EntityOutput {
    /// Top-level response key (alias or field name).
    pub response_key: String,
    /// Entity type name, e.g. "Property". Cross-checked against EntityFetch nodes for the
    /// same response key to detect plan-vs-executor drift in tests.
    #[allow(dead_code)]
    pub entity_type: String,
    /// Single key field, e.g. "id". Phase 3 uses single-field keys only.
    #[allow(dead_code)]
    pub key_field: String,
}

/// One `_entities` round-trip for an extending subgraph. Issued in the second wave after
/// every RootFetch has returned its data.
#[derive(Debug, Clone)]
pub struct EntityFetch {
    pub subgraph: String,
    /// Response key in the merged data where the entities to extend live (e.g. "searchProperties").
    pub from_response_key: String,
    /// Entity type name (e.g. "Property"). Used both for the inline fragment and for
    /// matching `__typename` on child responses.
    pub entity_type: String,
    pub key_field: String,
    /// Variable declarations to send (`($representations: [_Any!]!, ...)`). Stored on the node
    /// for diagnostic logs; the actual variables payload is built by the executor.
    #[allow(dead_code)]
    pub variable_definitions_text: String,
    /// The full GraphQL document we will POST.
    pub query: String,
    /// Names of the fields this fetch contributes. Used by the executor to merge results.
    pub contributed_fields: Vec<String>,
}

#[derive(Debug)]
pub enum PlanOutcome {
    Plan(PlanNode),
    Errors(Vec<GraphQLError>),
}

/// Build an [`ExecutionPlan`] for the given parsed operation.
pub fn plan(
    parsed: &ParsedQuery,
    op: &OperationView,
    supergraph: &SupergraphSchema,
) -> PlanOutcome {
    let root_type = match op.kind {
        OperationKind::Query => supergraph.query_type.clone(),
        OperationKind::Mutation => match supergraph.mutation_type.clone() {
            Some(t) => t,
            None => {
                return PlanOutcome::Errors(vec![GraphQLError::new(
                    "supergraph does not declare a mutation root type",
                )
                .with_code("SUPERGRAPH_NO_MUTATION_ROOT")])
            }
        },
        OperationKind::Subscription => {
            return PlanOutcome::Errors(vec![GraphQLError::new(
                "subscriptions are not supported by the router yet",
            )
            .with_code("UNSUPPORTED_OPERATION_KIND")])
        }
    };

    let root_meta = match supergraph.types.get(&root_type) {
        Some(m) => m,
        None => {
            return PlanOutcome::Errors(vec![GraphQLError::new(format!(
                "supergraph has no metadata for root type `{}`",
                root_type
            ))
            .with_code("SUPERGRAPH_MISSING_ROOT_TYPE")])
        }
    };

    // Re-parse the source so we can walk into nested selection sets. The cost is negligible
    // and avoids leaking apollo-parser's lifetimes across modules.
    let cst = apollo_parser::Parser::new(&parsed.source).parse();
    let target_op_name = op.name.as_deref();
    let op_node = match find_operation(&cst, target_op_name) {
        Some(o) => o,
        None => {
            return PlanOutcome::Errors(vec![GraphQLError::new(
                "internal: parsed operation not found in re-parsed CST",
            )
            .with_code("PLANNER_INTERNAL")])
        }
    };

    let selection_set = match op_node.selection_set() {
        Some(s) => s,
        None => {
            return PlanOutcome::Errors(vec![GraphQLError::new(
                "operation has no selection set",
            )
            .with_code("PLANNER_INTERNAL")])
        }
    };

    // ---- Step 1: group top-level fields by owning subgraph ------------------------------
    let mut errors: Vec<GraphQLError> = Vec::new();
    let mut by_subgraph: BTreeMap<String, Vec<TopLevelField>> = BTreeMap::new();

    for sel in selection_set.selections() {
        if let cst::Selection::Field(f) = sel {
            let info = match TopLevelField::from(&f, &parsed.source) {
                Some(i) => i,
                None => continue,
            };
            let owners = root_meta.field_owners(&info.field_name);
            if owners.is_empty() {
                let path_seg = crate::error::PathSegment::Key(info.response_key.clone());
                errors.push(
                    GraphQLError::new(format!(
                        "No subgraph owns top-level field `{}` on `{}`. Re-run the supergraph composer.",
                        info.field_name, root_type
                    ))
                    .with_path(vec![path_seg])
                    .with_code("FIELD_NOT_OWNED"),
                );
                continue;
            }
            let owner = owners[0].clone();
            by_subgraph.entry(owner).or_default().push(info);
        }
    }
    if !errors.is_empty() {
        return PlanOutcome::Errors(errors);
    }
    if by_subgraph.is_empty() {
        return PlanOutcome::Errors(vec![GraphQLError::new(
            "operation contains no top-level fields the router can dispatch",
        )
        .with_code("EMPTY_OPERATION")]);
    }

    let header_kind = match op.kind {
        OperationKind::Query => "query",
        OperationKind::Mutation => "mutation",
        OperationKind::Subscription => "subscription",
    };
    let name_part = op
        .name
        .as_deref()
        .map(|n| format!(" {}", n))
        .unwrap_or_default();
    let var_defs = &op.variable_definitions_text;

    // ---- Step 2: for each top-level field, classify nested selections -------------------
    // Each (target_subgraph, response_key, entity_type) combination becomes one EntityFetch.
    let mut entity_fetches_by_key: BTreeMap<(String, String, String), EntityFetchAccumulator> =
        BTreeMap::new();

    let mut root_fetches: Vec<RootFetch> = Vec::with_capacity(by_subgraph.len());
    for (subgraph_name, fields) in by_subgraph {
        let mut root_field_strs: Vec<String> = Vec::with_capacity(fields.len());
        let mut entity_outputs: Vec<EntityOutput> = Vec::new();

        for tlf in &fields {
            let return_type = root_meta
                .field_return_type(&tlf.field_name)
                .unwrap_or_default();
            let entity_meta = supergraph.types.get(return_type);
            let parent_is_entity = entity_meta.map(|m| m.is_entity()).unwrap_or(false);

            // Always pull back __typename + the key field for entity returns so we can
            // build representations later.
            let mut kept_inner = String::new();
            let key_field = entity_meta
                .and_then(|m| m.key_field(&subgraph_name))
                .unwrap_or("id")
                .to_string();

            if parent_is_entity {
                kept_inner.push_str("__typename ");
                kept_inner.push_str(&key_field);
                kept_inner.push(' ');
            }

            let mut leaf_only_field = true;

            if let Some(inner_set) = tlf.field.selection_set() {
                for inner_sel in inner_set.selections() {
                    if let cst::Selection::Field(inner_field) = inner_sel {
                        let inner_name = inner_field
                            .name()
                            .map(|n| n.syntax().text().to_string())
                            .unwrap_or_default();
                        if inner_name == "__typename" {
                            // Already injected.
                            continue;
                        }
                        let owners = entity_meta
                            .map(|m| m.field_owners(&inner_name))
                            .unwrap_or_default()
                            .to_vec();

                        if owners.is_empty() {
                            // Field unknown in the supergraph -- forward as-is and let the
                            // owning subgraph reject it. This handles ad-hoc cases where the
                            // supergraph metadata is incomplete.
                            kept_inner.push_str(&parsed.source[selection_byte_range(&inner_field)]);
                            kept_inner.push(' ');
                            continue;
                        }

                        if owners.contains(&subgraph_name) {
                            // Same subgraph -- keep the field verbatim (preserves args + nested).
                            kept_inner.push_str(&parsed.source[selection_byte_range(&inner_field)]);
                            kept_inner.push(' ');
                            leaf_only_field = false;
                        } else {
                            // Extending subgraph -- record an entity fetch.
                            let target = owners[0].clone();
                            let key = (
                                target.clone(),
                                tlf.response_key.clone(),
                                return_type.to_string(),
                            );
                            let acc = entity_fetches_by_key
                                .entry(key)
                                .or_insert_with(|| EntityFetchAccumulator::new(key_field.clone()));
                            acc.fields.push(parsed.source[selection_byte_range(&inner_field)].to_string());
                            acc.contributed_field_names.push(inner_name);
                        }
                    }
                }
            }

            if parent_is_entity {
                entity_outputs.push(EntityOutput {
                    response_key: tlf.response_key.clone(),
                    entity_type: return_type.to_string(),
                    key_field: key_field.clone(),
                });
            }

            // Reprint the top-level field call: alias + name + args, then our filtered set.
            let head = top_level_head(parsed, tlf);
            if parent_is_entity {
                root_field_strs.push(format!("{} {{ {} }}", head, kept_inner.trim()));
            } else if !kept_inner.trim().is_empty() {
                // Non-entity object with nested fields preserved.
                root_field_strs.push(format!("{} {{ {} }}", head, kept_inner.trim()));
            } else if !leaf_only_field && tlf.field.selection_set().is_some() {
                // Object return without a nested split; just pass through original source.
                root_field_strs.push(parsed.source[tlf.source_range.clone()].to_string());
            } else if tlf.field.selection_set().is_some() {
                // No nested splitting needed; pass-through.
                root_field_strs.push(parsed.source[tlf.source_range.clone()].to_string());
            } else {
                // Scalar leaf field.
                root_field_strs.push(head);
            }
        }

        let body = format!("{{ {} }}", root_field_strs.join(" "));
        let query = format!("{}{}{} {}", header_kind, name_part, var_defs, body);

        root_fetches.push(RootFetch {
            subgraph: subgraph_name,
            operation_kind: op.kind,
            operation_name: op.name.clone(),
            variable_definitions_text: var_defs.clone(),
            query,
            response_keys: fields.iter().map(|f| f.response_key.clone()).collect(),
            entity_outputs,
        });
    }

    // ---- Step 3: lower entity-fetch accumulators to EntityFetch nodes -------------------
    let mut entity_fetches: Vec<EntityFetch> = Vec::with_capacity(entity_fetches_by_key.len());
    for ((subgraph, response_key, entity_type), acc) in entity_fetches_by_key {
        let key_field = acc.key_field;
        let inner = acc.fields.join(" ");
        let var_defs_str = compose_variable_definitions(var_defs, "$representations: [_Any!]!");
        let query = format!(
            "query{}{} {{ _entities(representations: $representations) {{ ... on {} {{ {} }} }} }}",
            name_part, var_defs_str, entity_type, inner
        );
        entity_fetches.push(EntityFetch {
            subgraph,
            from_response_key: response_key,
            entity_type,
            key_field,
            variable_definitions_text: var_defs_str,
            query,
            contributed_fields: acc.contributed_field_names,
        });
    }

    // ---- Step 4: assemble plan tree ----------------------------------------------------
    let root_parallel = if root_fetches.len() == 1 {
        PlanNode::RootFetch(root_fetches.pop().unwrap())
    } else {
        PlanNode::Parallel(root_fetches.into_iter().map(PlanNode::RootFetch).collect())
    };

    if entity_fetches.is_empty() {
        return PlanOutcome::Plan(root_parallel);
    }

    let entity_parallel = if entity_fetches.len() == 1 {
        PlanNode::EntityFetch(entity_fetches.pop().unwrap())
    } else {
        PlanNode::Parallel(
            entity_fetches
                .into_iter()
                .map(PlanNode::EntityFetch)
                .collect(),
        )
    };

    PlanOutcome::Plan(PlanNode::Sequence(vec![root_parallel, entity_parallel]))
}

// ---------------------------------------------------------------------------- helpers ---

/// Information extracted about one top-level field selection.
struct TopLevelField {
    field_name: String,
    response_key: String,
    /// Source byte range covering the entire field including its selection set.
    source_range: std::ops::Range<usize>,
    /// CST node for nested traversal.
    field: cst::Field,
}

impl TopLevelField {
    fn from(f: &cst::Field, _source: &str) -> Option<Self> {
        let field_name = f.name()?.syntax().text().to_string();
        let response_key = f
            .alias()
            .and_then(|a| a.name())
            .map(|n| n.syntax().text().to_string())
            .unwrap_or_else(|| field_name.clone());
        let r = f.syntax().text_range();
        Some(TopLevelField {
            field_name,
            response_key,
            source_range: usize::from(r.start())..usize::from(r.end()),
            field: f.clone(),
        })
    }
}

/// Slice the byte range of a Field node covering name + arguments + selection set.
fn selection_byte_range(f: &cst::Field) -> std::ops::Range<usize> {
    let r = f.syntax().text_range();
    usize::from(r.start())..usize::from(r.end())
}

/// Reprint a top-level field's "head" (alias + name + arguments) without its selection set.
/// We could just slice the source up to the `{`, but doing it from the CST is more robust
/// against whitespace/comment placement.
fn top_level_head(parsed: &ParsedQuery, tlf: &TopLevelField) -> String {
    if let Some(set) = tlf.field.selection_set() {
        let set_start = usize::from(set.syntax().text_range().start());
        let head_end = set_start.min(tlf.source_range.end);
        parsed.source[tlf.source_range.start..head_end]
            .trim_end()
            .to_string()
    } else {
        parsed.source[tlf.source_range.clone()].to_string()
    }
}

/// Inject `$representations: [_Any!]!` into the existing variable-definitions text. Handles
/// both the empty case (no parens) and the with-parens case.
fn compose_variable_definitions(existing: &str, addition: &str) -> String {
    let trimmed = existing.trim();
    if trimmed.is_empty() {
        return format!("({})", addition);
    }
    // existing looks like `($foo: T, $bar: U)`. Insert addition before the closing paren.
    if let Some(close_idx) = trimmed.rfind(')') {
        let prefix = &trimmed[..close_idx];
        let suffix = &trimmed[close_idx..];
        // Add a comma if the prefix has any user-supplied variable.
        let separator = if prefix.trim_end_matches(['(', ' ', '\t']).ends_with('(') {
            ""
        } else {
            ", "
        };
        return format!("{}{}{}{}", prefix, separator, addition, suffix);
    }
    format!("({})", addition)
}

/// Find the named operation in the re-parsed CST. Falls back to the first operation when no
/// name is provided (matches Phase 2.2 `resolve_operation`).
fn find_operation(cst: &SyntaxTree, name: Option<&str>) -> Option<cst::OperationDefinition> {
    for def in cst.document().definitions() {
        if let cst::Definition::OperationDefinition(op) = def {
            let op_name = op.name().map(|n| n.syntax().text().to_string());
            match (name, op_name.as_deref()) {
                (None, _) => return Some(op),
                (Some(want), Some(have)) if want == have => return Some(op),
                (Some(_), None) => continue,
                (Some(_), Some(_)) => continue,
            }
        }
    }
    None
}

/// Per-(target_subgraph, response_key, entity_type) accumulator used while we walk
/// nested selections. The key for the outer map already carries those identifiers; this
/// just tracks the fields we've decided to delegate.
struct EntityFetchAccumulator {
    key_field: String,
    /// Source slices for each contributed field (as written by the client, including nested).
    fields: Vec<String>,
    contributed_field_names: Vec<String>,
}

impl EntityFetchAccumulator {
    fn new(key_field: String) -> Self {
        Self {
            key_field,
            fields: Vec::new(),
            contributed_field_names: Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::parse_and_validate;

    fn fake_supergraph() -> &'static str {
        r#"
schema
  @link(url: "https://specs.apollo.dev/link/v1.0")
  @link(url: "https://specs.apollo.dev/join/v0.3", for: EXECUTION)
{
  query: Query
}

directive @join__field(graph: join__Graph!) repeatable on FIELD_DEFINITION
directive @join__graph(name: String!, url: String!) on ENUM_VALUE
directive @join__type(graph: join__Graph!, key: join__FieldSet) repeatable on OBJECT
scalar join__FieldSet

enum join__Graph {
  PROPERTY @join__graph(name: "property", url: "http://property:8081/graphql")
  PRICING @join__graph(name: "pricing", url: "http://pricing:8082/graphql")
  REVIEW @join__graph(name: "review", url: "http://review:8085/graphql")
}

type Query @join__type(graph: PROPERTY) @join__type(graph: PRICING) @join__type(graph: REVIEW) {
  property(id: ID!): Property @join__field(graph: PROPERTY)
  searchProperties(city: String!, limit: Int): [Property!]! @join__field(graph: PROPERTY)
}

type Property @join__type(graph: PROPERTY, key: "id") @join__type(graph: PRICING, key: "id") @join__type(graph: REVIEW, key: "id") {
  id: ID!
  name: String! @join__field(graph: PROPERTY)
  rating: Float! @join__field(graph: PROPERTY)
  price(checkIn: String, checkOut: String): Price @join__field(graph: PRICING)
  reviews(limit: Int): [Review!]! @join__field(graph: REVIEW)
}

type Price @join__type(graph: PRICING) { totalAmount: Float! @join__field(graph: PRICING) }
type Review @join__type(graph: REVIEW) { rating: Int! @join__field(graph: REVIEW) }
"#
    }

    fn supergraph() -> SupergraphSchema {
        SupergraphSchema::parse(fake_supergraph()).unwrap()
    }

    fn parsed(q: &str) -> ParsedQuery {
        match parse_and_validate(q) {
            crate::parser::ParseOutcome::Ok(p) => p,
            other => panic!("unexpected parse outcome: {:?}", other),
        }
    }

    #[test]
    fn plan_for_single_subgraph_query_uses_one_root_fetch() {
        let q = "{ searchProperties(city: \"Austin\", limit: 1) { id name } }";
        let p = parsed(q);
        let plan_node = match plan(&p, &p.operations[0], &supergraph()) {
            PlanOutcome::Plan(node) => node,
            PlanOutcome::Errors(e) => panic!("unexpected errors: {:?}", e),
        };
        match plan_node {
            PlanNode::RootFetch(rf) => {
                assert_eq!(rf.subgraph, "property");
                assert!(rf.query.contains("searchProperties(city: \"Austin\", limit: 1)"));
                assert!(rf.query.contains("__typename"));
                assert!(rf.query.contains("id"));
            }
            other => panic!("expected single RootFetch, got {:?}", other),
        }
    }

    #[test]
    fn plan_with_extending_subgraph_emits_entity_fetch() {
        let q = "{ searchProperties(city: \"Austin\") { name price { totalAmount } reviews { rating } } }";
        let p = parsed(q);
        let plan_node = match plan(&p, &p.operations[0], &supergraph()) {
            PlanOutcome::Plan(node) => node,
            PlanOutcome::Errors(e) => panic!("unexpected errors: {:?}", e),
        };
        let (root_node, entity_node) = match plan_node {
            PlanNode::Sequence(mut nodes) => {
                assert_eq!(nodes.len(), 2);
                let entities = nodes.pop().unwrap();
                let roots = nodes.pop().unwrap();
                (roots, entities)
            }
            other => panic!("expected Sequence, got {:?}", other),
        };

        let root_fetch = match root_node {
            PlanNode::RootFetch(rf) => rf,
            PlanNode::Parallel(mut v) if v.len() == 1 => match v.pop().unwrap() {
                PlanNode::RootFetch(rf) => rf,
                _ => panic!(),
            },
            other => panic!("expected RootFetch, got {:?}", other),
        };
        assert_eq!(root_fetch.subgraph, "property");
        assert!(root_fetch.query.contains("searchProperties"));
        assert!(root_fetch.query.contains("name"));
        assert!(root_fetch.query.contains("__typename"));
        assert!(!root_fetch.query.contains("totalAmount"));
        assert!(!root_fetch.query.contains("rating"));
        assert_eq!(root_fetch.entity_outputs.len(), 1);
        assert_eq!(root_fetch.entity_outputs[0].entity_type, "Property");

        let entity_fetches: Vec<EntityFetch> = match entity_node {
            PlanNode::Parallel(v) => v
                .into_iter()
                .filter_map(|n| match n {
                    PlanNode::EntityFetch(ef) => Some(ef),
                    _ => None,
                })
                .collect(),
            PlanNode::EntityFetch(ef) => vec![ef],
            other => panic!("expected entity fetches, got {:?}", other),
        };
        assert_eq!(entity_fetches.len(), 2);
        let pricing_fetch = entity_fetches.iter().find(|e| e.subgraph == "pricing").unwrap();
        assert!(pricing_fetch.query.contains("_entities(representations: $representations)"));
        assert!(pricing_fetch.query.contains("... on Property"));
        assert!(pricing_fetch.query.contains("totalAmount"));
        let review_fetch = entity_fetches.iter().find(|e| e.subgraph == "review").unwrap();
        assert!(review_fetch.query.contains("rating"));
    }
}
