//! Subgraph registry — loaded from a YAML file at startup.
//!
//! Phase 3 changed the role of this file: field-level routing now comes from the
//! supergraph SDL (Phase 3.2 composer + Phase 3.3 router planner), so the YAML carries
//! ONLY per-subgraph URL overrides and timeouts. The supergraph SDL knows the URLs the
//! composer was pointed at, but at runtime we want to override those with the docker /
//! kubernetes service hostnames; that is what this file is for.
//!
//! Phase 2 (hand-rolled gateway) used a `query_fields` / `mutation_fields` list on every
//! subgraph entry; those keys are now ignored and the supergraph SDL is the source of
//! truth.

use serde::Deserialize;
use std::collections::HashMap;
use std::path::Path;

#[derive(Debug, Clone)]
pub struct SubgraphRegistry {
    pub subgraphs: HashMap<String, SubgraphConfig>,
}

#[derive(Debug, Clone)]
pub struct SubgraphConfig {
    pub name: String,
    pub url: String,
    pub timeout: std::time::Duration,
}

#[derive(Debug, Deserialize)]
struct RawConfig {
    subgraphs: HashMap<String, RawSubgraph>,
}

#[derive(Debug, Deserialize)]
struct RawSubgraph {
    url: String,
    #[serde(default = "default_timeout_ms")]
    timeout_ms: u64,
    /// Phase 2 leftover -- accepted (and ignored) so existing config files still load.
    #[serde(default)]
    #[allow(dead_code)]
    query_fields: Vec<String>,
    #[serde(default)]
    #[allow(dead_code)]
    mutation_fields: Vec<String>,
}

fn default_timeout_ms() -> u64 { 1000 }

impl SubgraphRegistry {
    pub fn from_yaml_file(path: impl AsRef<Path>) -> anyhow::Result<Self> {
        let raw = std::fs::read_to_string(path.as_ref()).map_err(|e| {
            anyhow::anyhow!(
                "failed to read subgraph config from {}: {}",
                path.as_ref().display(),
                e
            )
        })?;
        Self::from_yaml_str(&raw)
    }

    pub fn from_yaml_str(yaml: &str) -> anyhow::Result<Self> {
        // Expand ${VAR} and ${VAR:-default} from process env so containers and
        // local dev can share the same config file by setting/omitting env vars.
        let expanded = expand_env_vars(yaml);
        let raw: RawConfig = serde_yaml::from_str(&expanded)
            .map_err(|e| anyhow::anyhow!("invalid subgraph config: {}", e))?;

        let mut subgraphs = HashMap::with_capacity(raw.subgraphs.len());

        for (name, sg) in raw.subgraphs.into_iter() {
            let cfg = SubgraphConfig {
                name: name.clone(),
                url: sg.url,
                timeout: std::time::Duration::from_millis(sg.timeout_ms),
            };
            subgraphs.insert(name, cfg);
        }

        Ok(Self { subgraphs })
    }

    pub fn get(&self, name: &str) -> Option<&SubgraphConfig> {
        self.subgraphs.get(name)
    }

    /// Apply URL hints from a parsed supergraph schema. Subgraphs already in the registry
    /// keep their existing URL/timeout (so docker-compose / k8s service hostnames win over
    /// composition-time URLs). Subgraphs that exist in the supergraph but NOT in the YAML
    /// get a default entry using the composition-time URL with the default timeout.
    pub fn merge_supergraph_hints(
        &mut self,
        hints: &std::collections::BTreeMap<String, crate::supergraph::SubgraphInfo>,
    ) {
        for (name, info) in hints {
            if self.subgraphs.contains_key(name) {
                continue;
            }
            if let Some(url) = &info.url {
                self.subgraphs.insert(
                    name.clone(),
                    SubgraphConfig {
                        name: name.clone(),
                        url: url.clone(),
                        timeout: std::time::Duration::from_millis(default_timeout_ms()),
                    },
                );
            }
        }
    }
}

/// Expand `${VAR}` and `${VAR:-default}` references using process env. Unknown
/// vars without a default expand to an empty string.
fn expand_env_vars(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if i + 1 < bytes.len() && bytes[i] == b'$' && bytes[i + 1] == b'{' {
            if let Some(close) = s[i + 2..].find('}') {
                let inner = &s[i + 2..i + 2 + close];
                let (name, default) = match inner.find(":-") {
                    Some(pos) => (&inner[..pos], Some(&inner[pos + 2..])),
                    None => (inner, None),
                };
                let value = std::env::var(name)
                    .ok()
                    .unwrap_or_else(|| default.unwrap_or("").to_string());
                out.push_str(&value);
                i += 2 + close + 1;
                continue;
            }
        }
        // Advance one char (UTF-8 safe -- never split a multi-byte sequence).
        let mut j = i + 1;
        while j < bytes.len() && !s.is_char_boundary(j) {
            j += 1;
        }
        out.push_str(&s[i..j]);
        i = j;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loads_minimal_yaml() {
        let yaml = r#"
subgraphs:
  property:
    url: http://localhost:8081/graphql
"#;
        let r = SubgraphRegistry::from_yaml_str(yaml).unwrap();
        assert_eq!(
            r.get("property").unwrap().timeout,
            std::time::Duration::from_millis(1000)
        );
    }

    #[test]
    fn legacy_phase_2_keys_are_accepted_but_ignored() {
        let yaml = r#"
subgraphs:
  property:
    url: http://localhost:8081/graphql
    query_fields: [searchProperties]
    mutation_fields: []
"#;
        // Should still load even though those keys are now no-ops.
        let r = SubgraphRegistry::from_yaml_str(yaml).unwrap();
        assert!(r.get("property").is_some());
    }

    #[test]
    fn env_var_expansion_with_default() {
        std::env::remove_var("UNSET_FOR_TEST");
        let yaml = r#"
subgraphs:
  property:
    url: ${UNSET_FOR_TEST:-http://fallback/graphql}
"#;
        let r = SubgraphRegistry::from_yaml_str(yaml).unwrap();
        assert_eq!(r.get("property").unwrap().url, "http://fallback/graphql");
    }
}
