//! Parse and validate inbound GraphQL operations.
//!
//! Phase 2.2 scope: parse with apollo-parser (which gives us source ranges and
//! line/column for free), then run three structural validations that don't
//! require a schema:
//!
//!   1. The document must contain at least one operation.
//!   2. Multiple operations must all be named (no anonymous operations mixed in).
//!   3. Every variable referenced in a selection must be declared on the
//!      enclosing operation.
//!
//! Schema-aware validation (field existence, type checking, fragment-on-type)
//! arrives in Phase 3 once supergraph composition is in place.

use crate::error::{GraphQLError, Location, PathSegment};
use apollo_parser::cst::{self, CstNode};
use apollo_parser::{SyntaxKind, SyntaxNode};

/// Outcome of parsing + structural validation.
#[derive(Debug)]
pub enum ParseOutcome {
    /// Parse succeeded and structural validation passed.
    Ok(ParsedQuery),
    /// Parser produced one or more syntactic errors. Per the GraphQL-over-HTTP
    /// spec these are surfaced as HTTP 400.
    ParseErrors(Vec<GraphQLError>),
    /// Parse succeeded but one or more structural rules were violated. Per the
    /// GraphQL spec these are HTTP 200 with a populated `errors` array.
    ValidationErrors(Vec<GraphQLError>),
}

#[derive(Debug, Clone)]
pub struct ParsedQuery {
    /// The original source. Phase 2.3 slices source ranges from this to build
    /// per-subgraph sub-queries.
    pub source: String,

    /// All operations in the document (may be > 1 if the client uses
    /// `operationName` to select among them).
    pub operations: Vec<OperationView>,
}

#[derive(Debug, Clone)]
pub struct OperationView {
    pub name: Option<String>,
    pub kind: OperationKind,
    /// Source text of the variable declarations including parens, e.g.
    /// `($city: String!, $limit: Int = 10)`. Empty string when there are no
    /// declarations.
    pub variable_definitions_text: String,
    /// Source ranges of each top-level field selection (start..end byte offsets
    /// into [`ParsedQuery::source`]). Phase 3.3 planning re-parses the source via
    /// apollo-parser to walk into nested selections, so this slice is currently used
    /// only by tests, but the structural information is cheap to keep here.
    #[allow(dead_code)]
    pub top_level_selections: Vec<TopLevelSelection>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OperationKind {
    Query,
    Mutation,
    Subscription,
}

#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct TopLevelSelection {
    /// Field name (or alias name if aliased). For `foo: bar(...) { ... }` this
    /// is `bar` -- the name we look up in the SubgraphRegistry.
    pub field_name: String,
    /// Optional field alias. Used when assembling the merged response so the
    /// response key matches what the client asked for.
    pub response_key: String,
    /// Byte range in the original source that contains the entire field
    /// selection including any nested selection set.
    pub source_range: std::ops::Range<usize>,
}

/// Parse the supplied GraphQL document and run structural validations.
pub fn parse_and_validate(source: &str) -> ParseOutcome {
    let parser = apollo_parser::Parser::new(source);
    let cst = parser.parse();

    // Apollo-parser is recoverable -- it always returns a SyntaxTree but
    // surfaces parse errors here. We materialise them once so we don't depend
    // on whether `errors()` returns a slice, ExactSizeIterator, or plain Iterator.
    let mut parse_errs: Vec<GraphQLError> = Vec::new();
    for e in cst.errors() {
        let (line, column) = byte_offset_to_line_column(source, e.index());
        parse_errs.push(
            GraphQLError::new(format!("Syntax error: {}", e.message()))
                .with_location(line, column)
                .with_code("GRAPHQL_PARSE_FAILED"),
        );
    }
    if !parse_errs.is_empty() {
        return ParseOutcome::ParseErrors(parse_errs);
    }

    let document = cst.document();

    // ---- Validation rule 1: at least one operation ---------------------
    let operations: Vec<cst::OperationDefinition> = document
        .definitions()
        .filter_map(|d| match d {
            cst::Definition::OperationDefinition(op) => Some(op),
            _ => None,
        })
        .collect();

    if operations.is_empty() {
        return ParseOutcome::ValidationErrors(vec![
            GraphQLError::new("Document must contain at least one operation.")
                .with_code("GRAPHQL_VALIDATION_FAILED"),
        ]);
    }

    // ---- Validation rule 2: no anonymous operation when multiple sent --
    let mut errs: Vec<GraphQLError> = Vec::new();
    if operations.len() > 1 {
        for op in &operations {
            if op.name().is_none() {
                let pos = op.syntax().text_range().start();
                let (line, column) = byte_offset_to_line_column(source, usize::from(pos));
                errs.push(
                    GraphQLError::new(
                        "Anonymous operation cannot be used when the document contains multiple operations.",
                    )
                    .with_location(line as u32, column as u32)
                    .with_code("GRAPHQL_VALIDATION_FAILED"),
                );
            }
        }
    }

    // ---- Validation rule 3: declared variables cover all references ----
    let mut views: Vec<OperationView> = Vec::with_capacity(operations.len());
    for op in &operations {
        let declared = collect_declared_variables(op);
        let referenced = collect_referenced_variables(op.syntax());
        for (var_name, byte_pos) in &referenced {
            if !declared.contains(var_name) {
                let (line, column) = byte_offset_to_line_column(source, *byte_pos);
                errs.push(
                    GraphQLError::new(format!(
                        "Variable `${}` is referenced but never declared on the enclosing operation.",
                        var_name
                    ))
                    .with_location(line, column)
                    .with_code("GRAPHQL_VALIDATION_FAILED"),
                );
            }
        }

        // Build the OperationView (used by Phase 2.3 to fan out to subgraphs).
        let kind = match op
            .operation_type()
            .map(|t| t.syntax().text().to_string())
            .as_deref()
            .map(str::trim)
        {
            Some("mutation") => OperationKind::Mutation,
            Some("subscription") => OperationKind::Subscription,
            _ => OperationKind::Query, // shorthand `{ ... }` and explicit `query`
        };
        let var_def_text = op
            .variable_definitions()
            .map(|vd| vd.syntax().text().to_string())
            .unwrap_or_default();

        let mut top_levels = Vec::new();
        if let Some(set) = op.selection_set() {
            for sel in set.selections() {
                if let cst::Selection::Field(f) = sel {
                    let field_name = f
                        .name()
                        .map(|n| n.syntax().text().to_string())
                        .unwrap_or_default();
                    let response_key = f
                        .alias()
                        .and_then(|a| a.name())
                        .map(|n| n.syntax().text().to_string())
                        .unwrap_or_else(|| field_name.clone());
                    let range = f.syntax().text_range();
                    top_levels.push(TopLevelSelection {
                        field_name,
                        response_key,
                        source_range: usize::from(range.start())..usize::from(range.end()),
                    });
                }
            }
        }

        views.push(OperationView {
            name: op.name().map(|n| n.syntax().text().to_string()),
            kind,
            variable_definitions_text: var_def_text,
            top_level_selections: top_levels,
        });
    }

    if !errs.is_empty() {
        return ParseOutcome::ValidationErrors(errs);
    }

    ParseOutcome::Ok(ParsedQuery { source: source.to_string(), operations: views })
}

/// Resolve which [`OperationView`] to execute, given the optional
/// `operationName` from the request body. Single-operation documents are
/// auto-selected; multi-op documents require an explicit name.
pub fn resolve_operation<'a>(
    parsed: &'a ParsedQuery,
    operation_name: Option<&str>,
) -> Result<&'a OperationView, GraphQLError> {
    match (parsed.operations.as_slice(), operation_name) {
        ([single], None) => Ok(single),
        ([single], Some(name)) => {
            // If a name is supplied, it must match (or we fall back to single).
            if single.name.as_deref() == Some(name) || single.name.is_none() {
                Ok(single)
            } else {
                Err(GraphQLError::new(format!(
                    "Operation `{}` not found. Available: {}",
                    name,
                    single.name.as_deref().unwrap_or("(anonymous)")
                ))
                .with_code("GRAPHQL_VALIDATION_FAILED"))
            }
        }
        (_many, Some(name)) => parsed
            .operations
            .iter()
            .find(|o| o.name.as_deref() == Some(name))
            .ok_or_else(|| {
                GraphQLError::new(format!("Operation `{}` not found in the document.", name))
                    .with_code("GRAPHQL_VALIDATION_FAILED")
            }),
        (_many, None) => Err(GraphQLError::new(
            "Multiple operations sent but no `operationName` was supplied to choose between them.",
        )
        .with_code("GRAPHQL_VALIDATION_FAILED")),
    }
}

// ---------- helpers --------------------------------------------------------

fn collect_declared_variables(op: &cst::OperationDefinition) -> std::collections::HashSet<String> {
    let mut out = std::collections::HashSet::new();
    if let Some(defs) = op.variable_definitions() {
        for def in defs.variable_definitions() {
            if let Some(var) = def.variable() {
                if let Some(name) = var.name() {
                    out.insert(name.syntax().text().to_string());
                }
            }
        }
    }
    out
}

/// Walk an operation's syntax subtree and collect every `Variable` reference,
/// returning (name, byte_offset) pairs. We deliberately exclude the
/// `VARIABLE_DEFINITIONS` block so declarations don't count as references.
fn collect_referenced_variables(node: &SyntaxNode) -> Vec<(String, usize)> {
    let mut out = Vec::new();
    walk_for_variables(node, &mut out, /* inside_definitions */ false);
    out
}

fn walk_for_variables(node: &SyntaxNode, out: &mut Vec<(String, usize)>, inside_definitions: bool) {
    let kind = node.kind();
    let next_inside_definitions = inside_definitions || kind == SyntaxKind::VARIABLE_DEFINITIONS;

    if !next_inside_definitions && kind == SyntaxKind::VARIABLE {
        // The VARIABLE node contains a leading `$` token followed by a NAME node.
        if let Some(name_node) = node.children().find(|n| n.kind() == SyntaxKind::NAME) {
            let text = name_node.text().to_string();
            let pos = usize::from(name_node.text_range().start());
            out.push((text, pos));
        }
    }

    for child in node.children() {
        walk_for_variables(&child, out, next_inside_definitions);
    }
}

fn byte_offset_to_line_column(source: &str, byte_offset: usize) -> (u32, u32) {
    let mut line = 1u32;
    let mut column = 1u32;
    for (i, ch) in source.char_indices() {
        if i >= byte_offset {
            break;
        }
        if ch == '\n' {
            line += 1;
            column = 1;
        } else {
            column += 1;
        }
    }
    (line, column)
}

// `Location` and `PathSegment` are re-exported for downstream use.
#[allow(dead_code)]
fn _force_use(_: Location, _: PathSegment) {}

#[cfg(test)]
mod tests {
    use super::*;

    fn unwrap_ok(o: ParseOutcome) -> ParsedQuery {
        match o {
            ParseOutcome::Ok(p) => p,
            other => panic!("expected Ok, got {:?}", other),
        }
    }

    fn unwrap_validation(o: ParseOutcome) -> Vec<GraphQLError> {
        match o {
            ParseOutcome::ValidationErrors(e) => e,
            other => panic!("expected ValidationErrors, got {:?}", other),
        }
    }

    fn unwrap_parse_errors(o: ParseOutcome) -> Vec<GraphQLError> {
        match o {
            ParseOutcome::ParseErrors(e) => e,
            other => panic!("expected ParseErrors, got {:?}", other),
        }
    }

    /// Phase 2.2 happy path -- a valid named query with arguments and variable
    /// declarations parses into one OperationView with two top-level fields.
    #[test]
    fn happy_path_valid_query_parses() {
        let q = r#"query Listing($city: String!, $limit: Int = 5) {
  searchProperties(city: $city, limit: $limit) { id name rating }
  reviewSummary(propertyId: "abc") { average count }
}"#;
        let parsed = unwrap_ok(parse_and_validate(q));
        assert_eq!(parsed.operations.len(), 1);
        let op = &parsed.operations[0];
        assert_eq!(op.name.as_deref(), Some("Listing"));
        assert_eq!(op.kind, OperationKind::Query);
        assert!(op.variable_definitions_text.contains("$city: String!"));
        assert_eq!(op.top_level_selections.len(), 2);
        assert_eq!(op.top_level_selections[0].field_name, "searchProperties");
        assert_eq!(op.top_level_selections[1].field_name, "reviewSummary");
    }

    /// Phase 2.2 failure path #1 -- a syntax error returns ParseErrors with the
    /// line/column of the offending token populated. Per the GraphQL-over-HTTP
    /// spec these surface as HTTP 400 (handled in `handler.rs`).
    #[test]
    fn syntax_error_returns_parse_errors_with_location() {
        // Missing closing brace on the inner selection set.
        let q = "query Bad { searchProperties(city: \"Austin\" {";
        let errs = unwrap_parse_errors(parse_and_validate(q));
        assert!(!errs.is_empty(), "expected at least one parse error");
        for e in &errs {
            assert!(e.message.starts_with("Syntax error"), "message: {}", e.message);
            assert!(!e.locations.is_empty(), "parse errors must carry location info");
            let loc = &e.locations[0];
            assert!(loc.line >= 1 && loc.column >= 1);
            assert_eq!(
                e.extensions.as_ref().unwrap()["code"],
                serde_json::Value::String("GRAPHQL_PARSE_FAILED".to_string())
            );
        }
    }

    /// Phase 2.2 failure path #2 -- a query that references an undeclared
    /// variable yields a ValidationErrors outcome (HTTP 200 + errors body).
    #[test]
    fn undeclared_variable_is_a_validation_error() {
        let q = r#"query Missing { searchProperties(city: $unknownCity) { id } }"#;
        let errs = unwrap_validation(parse_and_validate(q));
        assert_eq!(errs.len(), 1);
        assert!(errs[0].message.contains("$unknownCity"));
        assert!(!errs[0].locations.is_empty());
        assert_eq!(
            errs[0].extensions.as_ref().unwrap()["code"],
            serde_json::Value::String("GRAPHQL_VALIDATION_FAILED".to_string())
        );
    }

    /// Phase 2.2 failure path #3 -- a document with multiple operations where
    /// any one of them is anonymous violates the spec.
    #[test]
    fn mixing_anonymous_and_named_operations_is_a_validation_error() {
        let q = r#"
            { searchProperties(city: "Austin") { id } }
            query Named { reviewSummary(propertyId: "x") { count } }
        "#;
        let errs = unwrap_validation(parse_and_validate(q));
        assert!(errs.iter().any(|e| e.message.to_lowercase().contains("anonymous")));
    }

    /// Bonus -- a syntactically-valid document that contains only a fragment
    /// definition (and zero operations) is rejected as a validation error.
    /// (A literally empty input is a parse error; this case is the legitimate
    /// "no operations" path the validator must catch.)
    #[test]
    fn document_without_operations_is_a_validation_error() {
        let q = "fragment PropertySummary on Property { id name rating }";
        let errs = unwrap_validation(parse_and_validate(q));
        assert!(errs.iter().any(|e| e.message.to_lowercase().contains("at least one operation")));
    }

    /// Resolution: a single-op document is auto-selected.
    #[test]
    fn resolve_operation_with_single_op_no_name() {
        let q = "{ searchProperties(city: \"Austin\") { id } }";
        let parsed = unwrap_ok(parse_and_validate(q));
        let op = resolve_operation(&parsed, None).unwrap();
        assert!(op.name.is_none());
    }

    /// Resolution: multi-op without operationName errors out.
    #[test]
    fn resolve_operation_with_many_ops_no_name_errors() {
        let q = r#"
            query A { searchProperties(city: "x") { id } }
            query B { reviewSummary(propertyId: "y") { count } }
        "#;
        let parsed = unwrap_ok(parse_and_validate(q));
        let err = resolve_operation(&parsed, None).unwrap_err();
        assert!(err.message.contains("Multiple operations"));
    }
}
