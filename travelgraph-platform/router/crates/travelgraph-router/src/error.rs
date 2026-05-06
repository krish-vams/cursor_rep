//! GraphQL-spec-compliant request/response/error types and helpers.

use serde::{Deserialize, Serialize};

/// Inbound GraphQL request body. Per the GraphQL-over-HTTP spec, `variables` and
/// `operationName` are optional. We default to an empty object for variables so
/// downstream code never has to special-case the missing field.
#[derive(Debug, Clone, Deserialize)]
pub struct GraphQLRequest {
    pub query: String,
    #[serde(default)]
    pub variables: serde_json::Value,
    #[serde(rename = "operationName")]
    pub operation_name: Option<String>,
}

/// Outbound GraphQL response body.
///
/// Serialisation rules (matching the GraphQL spec):
///   * `data` is always present (may be `null`).
///   * `errors` is omitted when empty.
///   * `extensions` is omitted when missing.
#[derive(Debug, Clone, Serialize)]
pub struct GraphQLResponse {
    pub data: serde_json::Value,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<GraphQLError>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub extensions: Option<serde_json::Value>,
}

impl GraphQLResponse {
    pub fn errors_only(errors: Vec<GraphQLError>) -> Self {
        Self { data: serde_json::Value::Null, errors, extensions: None }
    }
}

/// One entry in the `errors` array. The shape mirrors the GraphQL spec exactly:
/// `message` is required; `locations`, `path`, and `extensions` are optional.
#[derive(Debug, Clone, Serialize)]
pub struct GraphQLError {
    pub message: String,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub locations: Vec<Location>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub path: Vec<PathSegment>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub extensions: Option<serde_json::Value>,
}

impl GraphQLError {
    pub fn new(message: impl Into<String>) -> Self {
        Self { message: message.into(), locations: Vec::new(), path: Vec::new(), extensions: None }
    }

    pub fn with_location(mut self, line: u32, column: u32) -> Self {
        self.locations.push(Location { line, column });
        self
    }

    pub fn with_path(mut self, path: Vec<PathSegment>) -> Self {
        self.path = path;
        self
    }

    pub fn with_code(mut self, code: impl Into<String>) -> Self {
        let extensions = self.extensions.unwrap_or_else(|| serde_json::json!({}));
        let mut map = extensions.as_object().cloned().unwrap_or_default();
        map.insert("code".to_string(), serde_json::Value::String(code.into()));
        self.extensions = Some(serde_json::Value::Object(map));
        self
    }

    pub fn with_extension(mut self, key: impl Into<String>, value: serde_json::Value) -> Self {
        let extensions = self.extensions.unwrap_or_else(|| serde_json::json!({}));
        let mut map = extensions.as_object().cloned().unwrap_or_default();
        map.insert(key.into(), value);
        self.extensions = Some(serde_json::Value::Object(map));
        self
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Location {
    pub line: u32,
    pub column: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(untagged)]
pub enum PathSegment {
    Key(String),
    Index(usize),
}
