//! Supergraph schema parsing.
//!
//! Phase 3.3 replaces the hand-rolled [`SubgraphRegistry`] (Phase 2.3) with metadata derived
//! from a composed Apollo Federation v2 supergraph SDL. The composer (`schema-registry/composer`,
//! Phase 3.2) writes that file at composition time; the router reads it at startup.
//!
//! What we extract:
//!
//!   * **Subgraph catalogue** -- every value of `enum join__Graph` carries
//!     `@join__graph(name: "...", url: "...")`. The `name` is the canonical subgraph name we
//!     use in logs, config, and route plans; the `url` is the override used when the
//!     `subgraphs.yaml` file does not provide one.
//!
//!   * **Field ownership** -- for every named type, every field carries
//!     `@join__field(graph: GRAPH_NAME)` which tells us which subgraph owns/contributes that
//!     field. A field with no `@join__field` directive is unconditionally available in every
//!     home of that type (typically `id: ID!`). For root types (`Query`, `Mutation`) this is
//!     how we route the *initial* fetch.
//!
//!   * **Entity homes + keys** -- for entity types, every `@join__type(graph: G, key: "id")`
//!     directive declares G as a home of the type with a particular key. The first home
//!     (textual order in the SDL) is treated as the *owning* subgraph; the rest are
//!     extending subgraphs. Entity round-trips (Phase 3.4) hit only extending subgraphs.
//!
//! [`SubgraphRegistry`]: crate::config::SubgraphRegistry

use crate::error::GraphQLError;
use apollo_parser::cst::{self, CstNode, Definition, Value};
use std::collections::{BTreeMap, BTreeSet, HashMap};

/// Per-field metadata: which subgraphs contribute it and what (unwrapped) type it returns.
#[derive(Debug, Clone, Default)]
pub struct FieldDecl {
    /// Subgraph names that contribute this field (from `@join__field(graph:)`). For a field
    /// with no directive, this is set to every home of the parent type during back-fill.
    pub owners: Vec<String>,
    /// Unwrapped return type name (NonNull and List wrappers stripped). E.g. for a field
    /// declared as `[Property!]!` this is `"Property"`.
    pub return_type_name: String,
}

/// Parsed metadata for one composite type in the supergraph.
#[derive(Debug, Clone, Default)]
pub struct TypeMeta {
    /// Subgraph names that declare this type (have a `@join__type` directive).
    /// Order is the SDL order; the first home is the *owning* subgraph for entity round-trips.
    pub homes: Vec<String>,
    /// Per-home key field set (the strings inside `@join__type(key: "...")`).
    pub keys: BTreeMap<String, Vec<String>>,
    /// `field_name -> FieldDecl`.
    pub fields: BTreeMap<String, FieldDecl>,
}

impl TypeMeta {
    pub fn is_entity(&self) -> bool {
        !self.homes.is_empty() && self.keys.values().any(|k| !k.is_empty())
    }

    /// Subgraphs that contribute the given field. Returns an empty slice when the field
    /// is not declared on this type.
    pub fn field_owners(&self, field: &str) -> &[String] {
        self.fields.get(field).map(|f| f.owners.as_slice()).unwrap_or_default()
    }

    pub fn field_return_type(&self, field: &str) -> Option<&str> {
        self.fields.get(field).map(|f| f.return_type_name.as_str())
    }

    /// Pick a single subgraph for resolving the given field on this type. We prefer the
    /// subgraph that *exclusively* owns the field; if multiple subgraphs declare it (rare
    /// for our schemas) we pick the first one in SDL order.
    #[allow(dead_code)]
    pub fn primary_field_owner(&self, field: &str) -> Option<&str> {
        self.fields.get(field).and_then(|v| v.owners.first()).map(String::as_str)
    }

    /// Pick a key field for this type when calling `_entities` against the given subgraph.
    /// We prefer the subgraph's own declared key; falls back to any home's key.
    pub fn key_field(&self, subgraph: &str) -> Option<&str> {
        if let Some(k) = self.keys.get(subgraph).and_then(|v| v.first()) {
            return Some(k.as_str());
        }
        self.keys.values().find_map(|v| v.first()).map(String::as_str)
    }
}

#[derive(Debug, Clone)]
pub struct SubgraphInfo {
    /// Canonical subgraph name as declared in `enum join__Graph`. Kept on the struct so
    /// callers (notably the registry merge step) can iterate without juggling the map key.
    #[allow(dead_code)]
    pub name: String,
    pub url: Option<String>,
}

/// Parsed metadata for the whole supergraph schema. The router consults this for every
/// incoming query.
#[derive(Debug, Clone)]
pub struct SupergraphSchema {
    pub subgraphs: BTreeMap<String, SubgraphInfo>,
    pub types: BTreeMap<String, TypeMeta>,
    pub query_type: String,
    pub mutation_type: Option<String>,
    /// Subscriptions are not currently dispatched by the executor; the field is captured to
    /// surface "supergraph declares subscriptions" diagnostics rather than to drive routing.
    #[allow(dead_code)]
    pub subscription_type: Option<String>,
}

impl SupergraphSchema {
    /// Parse a supergraph SDL string into the in-memory shape used by the planner and
    /// executor. Returns a structured error on the first parse failure.
    pub fn parse(sdl: &str) -> Result<Self, GraphQLError> {
        let parser = apollo_parser::Parser::new(sdl);
        let cst = parser.parse();
        let parse_errs: Vec<_> = cst.errors().collect();
        if !parse_errs.is_empty() {
            return Err(GraphQLError::new(format!(
                "supergraph SDL did not parse: {}",
                parse_errs[0].message()
            ))
            .with_code("SUPERGRAPH_PARSE_FAILED"));
        }

        let mut graph_enum: HashMap<String, GraphEnumValue> = HashMap::new();
        let mut types: BTreeMap<String, TypeMeta> = BTreeMap::new();
        let mut query_type = "Query".to_string();
        let mut mutation_type: Option<String> = None;
        let mut subscription_type: Option<String> = None;

        for def in cst.document().definitions() {
            match def {
                Definition::SchemaDefinition(s) => {
                    for op in s.root_operation_type_definitions() {
                        if let (Some(kind), Some(name)) = (op.operation_type(), op.named_type()) {
                            let kind_text = kind.syntax().text().to_string();
                            let name_text = name
                                .name()
                                .map(|n| n.syntax().text().to_string())
                                .unwrap_or_default();
                            match kind_text.trim() {
                                "query" => query_type = name_text,
                                "mutation" => mutation_type = Some(name_text),
                                "subscription" => subscription_type = Some(name_text),
                                _ => {}
                            }
                        }
                    }
                }
                Definition::SchemaExtension(s) => {
                    for op in s.root_operation_type_definitions() {
                        if let (Some(kind), Some(name)) = (op.operation_type(), op.named_type()) {
                            let kind_text = kind.syntax().text().to_string();
                            let name_text = name
                                .name()
                                .map(|n| n.syntax().text().to_string())
                                .unwrap_or_default();
                            match kind_text.trim() {
                                "query" => query_type = name_text,
                                "mutation" => mutation_type = Some(name_text),
                                "subscription" => subscription_type = Some(name_text),
                                _ => {}
                            }
                        }
                    }
                }
                Definition::EnumTypeDefinition(e) => {
                    let name = e
                        .name()
                        .map(|n| n.syntax().text().to_string())
                        .unwrap_or_default();
                    if name == "join__Graph" {
                        if let Some(values) = e.enum_values_definition() {
                            for val in values.enum_value_definitions() {
                                if let Some(enum_name) = val
                                    .enum_value()
                                    .and_then(|ev| ev.name())
                                    .map(|n| n.syntax().text().to_string())
                                {
                                    let mut info = GraphEnumValue {
                                        enum_name: enum_name.clone(),
                                        graph_name: enum_name.to_lowercase(),
                                        url: None,
                                    };
                                    if let Some(directives) = val.directives() {
                                        for d in directives.directives() {
                                            let dn = directive_name(&d);
                                            if dn == "join__graph" {
                                                if let Some(s) = directive_string_arg(&d, "name") {
                                                    info.graph_name = s;
                                                }
                                                if let Some(s) = directive_string_arg(&d, "url") {
                                                    info.url = Some(s);
                                                }
                                            }
                                        }
                                    }
                                    graph_enum.insert(enum_name, info);
                                }
                            }
                        }
                    }
                }
                Definition::ObjectTypeDefinition(t) => {
                    collect_type(&t, &graph_enum, &mut types);
                }
                Definition::InterfaceTypeDefinition(t) => {
                    collect_interface(&t, &graph_enum, &mut types);
                }
                _ => {}
            }
        }

        // Backfill: for fields with NO @join__field directive, the field is implicit in every
        // home of the type. Set its owner list to all homes.
        for meta in types.values_mut() {
            let homes = meta.homes.clone();
            for decl in meta.fields.values_mut() {
                if decl.owners.is_empty() {
                    decl.owners = homes.clone();
                }
            }
        }

        let mut subgraphs: BTreeMap<String, SubgraphInfo> = BTreeMap::new();
        for v in graph_enum.values() {
            subgraphs.insert(
                v.graph_name.clone(),
                SubgraphInfo { name: v.graph_name.clone(), url: v.url.clone() },
            );
        }

        Ok(SupergraphSchema {
            subgraphs,
            types,
            query_type,
            mutation_type,
            subscription_type,
        })
    }

    /// Distinct subgraphs referenced anywhere in the supergraph (for boot-time validation).
    #[allow(dead_code)]
    pub fn referenced_subgraphs(&self) -> BTreeSet<String> {
        let mut out: BTreeSet<String> = self.subgraphs.keys().cloned().collect();
        for meta in self.types.values() {
            out.extend(meta.homes.iter().cloned());
            for decl in meta.fields.values() {
                out.extend(decl.owners.iter().cloned());
            }
        }
        out
    }
}

#[derive(Debug, Clone)]
struct GraphEnumValue {
    #[allow(dead_code)]
    enum_name: String,
    graph_name: String,
    url: Option<String>,
}

fn collect_type(
    t: &cst::ObjectTypeDefinition,
    graphs: &HashMap<String, GraphEnumValue>,
    types: &mut BTreeMap<String, TypeMeta>,
) {
    let name = match t.name() {
        Some(n) => n.syntax().text().to_string(),
        None => return,
    };

    let entry = types.entry(name).or_default();

    if let Some(directives) = t.directives() {
        for d in directives.directives() {
            if directive_name(&d) == "join__type" {
                let graph_enum = directive_enum_arg(&d, "graph");
                let key = directive_string_arg(&d, "key").unwrap_or_default();
                if let Some(g) = graph_enum.and_then(|e| graphs.get(&e)) {
                    if !entry.homes.contains(&g.graph_name) {
                        entry.homes.push(g.graph_name.clone());
                    }
                    let parsed_keys = parse_key_field_set(&key);
                    entry.keys.insert(g.graph_name.clone(), parsed_keys);
                }
            }
        }
    }

    if let Some(fields_def) = t.fields_definition() {
        for fd in fields_def.field_definitions() {
            if let Some(field_name) = fd.name() {
                let fn_text = field_name.syntax().text().to_string();
                let mut owners: Vec<String> = Vec::new();
                if let Some(directives) = fd.directives() {
                    for d in directives.directives() {
                        if directive_name(&d) == "join__field" {
                            if let Some(g) =
                                directive_enum_arg(&d, "graph").and_then(|e| graphs.get(&e))
                            {
                                if !owners.contains(&g.graph_name) {
                                    owners.push(g.graph_name.clone());
                                }
                            }
                        }
                    }
                }
                let return_type_name = fd.ty().map(unwrap_type_to_name).unwrap_or_default();
                entry
                    .fields
                    .insert(fn_text, FieldDecl { owners, return_type_name });
            }
        }
    }
}

fn collect_interface(
    t: &cst::InterfaceTypeDefinition,
    graphs: &HashMap<String, GraphEnumValue>,
    types: &mut BTreeMap<String, TypeMeta>,
) {
    let name = match t.name() {
        Some(n) => n.syntax().text().to_string(),
        None => return,
    };

    let entry = types.entry(name).or_default();

    if let Some(directives) = t.directives() {
        for d in directives.directives() {
            if directive_name(&d) == "join__type" {
                if let Some(g) = directive_enum_arg(&d, "graph").and_then(|e| graphs.get(&e)) {
                    if !entry.homes.contains(&g.graph_name) {
                        entry.homes.push(g.graph_name.clone());
                    }
                }
            }
        }
    }

    if let Some(fields_def) = t.fields_definition() {
        for fd in fields_def.field_definitions() {
            if let Some(field_name) = fd.name() {
                let fn_text = field_name.syntax().text().to_string();
                let mut owners: Vec<String> = Vec::new();
                if let Some(directives) = fd.directives() {
                    for d in directives.directives() {
                        if directive_name(&d) == "join__field" {
                            if let Some(g) =
                                directive_enum_arg(&d, "graph").and_then(|e| graphs.get(&e))
                            {
                                if !owners.contains(&g.graph_name) {
                                    owners.push(g.graph_name.clone());
                                }
                            }
                        }
                    }
                }
                let return_type_name = fd.ty().map(unwrap_type_to_name).unwrap_or_default();
                entry
                    .fields
                    .insert(fn_text, FieldDecl { owners, return_type_name });
            }
        }
    }
}

fn unwrap_type_to_name(t: cst::Type) -> String {
    match t {
        cst::Type::NamedType(nt) => nt
            .name()
            .map(|n| n.syntax().text().to_string())
            .unwrap_or_default(),
        cst::Type::ListType(lt) => lt.ty().map(unwrap_type_to_name).unwrap_or_default(),
        cst::Type::NonNullType(nnt) => {
            if let Some(named) = nnt.named_type() {
                named
                    .name()
                    .map(|n| n.syntax().text().to_string())
                    .unwrap_or_default()
            } else if let Some(list) = nnt.list_type() {
                list.ty().map(unwrap_type_to_name).unwrap_or_default()
            } else {
                String::new()
            }
        }
    }
}

fn directive_name(d: &cst::Directive) -> String {
    d.name().map(|n| n.syntax().text().to_string()).unwrap_or_default()
}

fn directive_enum_arg(d: &cst::Directive, arg: &str) -> Option<String> {
    let args = d.arguments()?;
    for a in args.arguments() {
        let name = a.name()?.syntax().text().to_string();
        if name == arg {
            if let Some(Value::EnumValue(ev)) = a.value() {
                return ev.name().map(|n| n.syntax().text().to_string());
            }
        }
    }
    None
}

fn directive_string_arg(d: &cst::Directive, arg: &str) -> Option<String> {
    let args = d.arguments()?;
    for a in args.arguments() {
        let name = a.name()?.syntax().text().to_string();
        if name == arg {
            if let Some(Value::StringValue(s)) = a.value() {
                return Some(unquote_graphql_string(&s.syntax().text().to_string()));
            }
        }
    }
    None
}

fn unquote_graphql_string(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.starts_with("\"\"\"") && trimmed.ends_with("\"\"\"") && trimmed.len() >= 6 {
        trimmed[3..trimmed.len() - 3].to_string()
    } else if trimmed.starts_with('"') && trimmed.ends_with('"') && trimmed.len() >= 2 {
        let inner = &trimmed[1..trimmed.len() - 1];
        let mut out = String::with_capacity(inner.len());
        let mut chars = inner.chars();
        while let Some(c) = chars.next() {
            if c == '\\' {
                match chars.next() {
                    Some('"') => out.push('"'),
                    Some('\\') => out.push('\\'),
                    Some('/') => out.push('/'),
                    Some('n') => out.push('\n'),
                    Some('r') => out.push('\r'),
                    Some('t') => out.push('\t'),
                    Some(other) => {
                        out.push('\\');
                        out.push(other);
                    }
                    None => out.push('\\'),
                }
            } else {
                out.push(c);
            }
        }
        out
    } else {
        trimmed.to_string()
    }
}

/// Parse a federation field set like `"id"` or `"id name"` into a list of field names.
/// Phase 3 only uses single-field keys (`id`), but this is a small generalisation.
fn parse_key_field_set(s: &str) -> Vec<String> {
    s.split_ascii_whitespace().map(|t| t.to_string()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A minimal fake supergraph SDL that exercises the directives we care about. Real
    /// supergraphs have a lot more boilerplate but this is sufficient to exercise parsing.
    fn fake_supergraph() -> String {
        r#"
schema
  @link(url: "https://specs.apollo.dev/link/v1.0")
  @link(url: "https://specs.apollo.dev/join/v0.3", for: EXECUTION)
{
  query: Query
}

directive @join__field(graph: join__Graph!, requires: join__FieldSet, provides: join__FieldSet, type: String, external: Boolean, override: String, usedOverridden: Boolean) repeatable on FIELD_DEFINITION | INPUT_FIELD_DEFINITION
directive @join__graph(name: String!, url: String!) on ENUM_VALUE
directive @join__type(graph: join__Graph!, key: join__FieldSet, extension: Boolean! = false, resolvable: Boolean! = true, isInterfaceObject: Boolean! = false) repeatable on OBJECT | INTERFACE | UNION | ENUM | INPUT_OBJECT | SCALAR

scalar join__FieldSet

enum join__Graph {
  PROPERTY @join__graph(name: "property", url: "http://property:8081/graphql")
  PRICING @join__graph(name: "pricing", url: "http://pricing:8082/graphql")
  REVIEW @join__graph(name: "review", url: "http://review:8085/graphql")
}

type Query
  @join__type(graph: PROPERTY)
  @join__type(graph: PRICING)
  @join__type(graph: REVIEW)
{
  property(id: ID!): Property @join__field(graph: PROPERTY)
  searchProperties(city: String!, limit: Int = 20): [Property!]! @join__field(graph: PROPERTY)
  price(propertyId: ID!): Price @join__field(graph: PRICING)
  reviewSummary(propertyId: ID!): ReviewSummary @join__field(graph: REVIEW)
}

type Property
  @join__type(graph: PROPERTY, key: "id")
  @join__type(graph: PRICING, key: "id")
  @join__type(graph: REVIEW, key: "id")
{
  id: ID!
  name: String! @join__field(graph: PROPERTY)
  rating: Float! @join__field(graph: PROPERTY)
  price(checkIn: String, checkOut: String): Price @join__field(graph: PRICING)
  reviews(limit: Int = 10): [Review!]! @join__field(graph: REVIEW)
  reviewSummary: ReviewSummary! @join__field(graph: REVIEW)
}

type Price @join__type(graph: PRICING) { totalAmount: Float! @join__field(graph: PRICING) }
type Review @join__type(graph: REVIEW) { rating: Int! @join__field(graph: REVIEW) }
type ReviewSummary @join__type(graph: REVIEW) { average: Float! @join__field(graph: REVIEW) count: Int! @join__field(graph: REVIEW) }
"#
        .to_string()
    }

    #[test]
    fn parses_subgraphs_from_join_graph_enum() {
        let s = SupergraphSchema::parse(&fake_supergraph()).unwrap();
        assert!(s.subgraphs.contains_key("property"));
        assert!(s.subgraphs.contains_key("pricing"));
        assert!(s.subgraphs.contains_key("review"));
        assert_eq!(
            s.subgraphs.get("property").unwrap().url.as_deref(),
            Some("http://property:8081/graphql")
        );
    }

    #[test]
    fn extracts_query_field_owners() {
        let s = SupergraphSchema::parse(&fake_supergraph()).unwrap();
        let query = s.types.get(&s.query_type).unwrap();
        assert_eq!(query.field_owners("searchProperties"), &["property".to_string()]);
        assert_eq!(query.field_owners("price"), &["pricing".to_string()]);
        assert_eq!(query.field_owners("reviewSummary"), &["review".to_string()]);
    }

    #[test]
    fn extracts_property_homes_and_field_owners() {
        let s = SupergraphSchema::parse(&fake_supergraph()).unwrap();
        let prop = s.types.get("Property").unwrap();
        assert_eq!(prop.homes, vec!["property", "pricing", "review"]);
        assert!(prop.is_entity());
        assert_eq!(prop.field_owners("name"), &["property".to_string()]);
        assert_eq!(prop.field_owners("price"), &["pricing".to_string()]);
        assert_eq!(prop.field_owners("reviews"), &["review".to_string()]);
        // `id` has no @join__field so it's available in every home.
        let id_owners = prop.field_owners("id");
        assert!(id_owners.contains(&"property".to_string()));
        assert!(id_owners.contains(&"pricing".to_string()));
        assert!(id_owners.contains(&"review".to_string()));
    }

    #[test]
    fn parse_failure_returns_structured_error() {
        let result = SupergraphSchema::parse("type Foo { ");
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(
            err.message.contains("supergraph SDL did not parse"),
            "unexpected error: {}",
            err.message
        );
    }
}
