# travelgraph-composer

Kotlin CLI that produces a federated **supergraph SDL** by:

1. POSTing `query { _service { sdl } }` to each subgraph (Phase 3.1 wires this up via
   `graphql-kotlin-federation`),
2. Shelling out to a small Node script that calls
   [`@apollo/composition`](https://www.npmjs.com/package/@apollo/composition)'s
   `composeServices`,
3. Writing the result to `schema-registry/output/supergraph.graphql` (or any path you
   configure via `output:` in `composer-config.yaml`).

The router (Phase 3.3) reads that file at startup; the composer is the single tool that
moves SDL across the federation boundary.

## Why a Node subprocess?

`@apollo/composition` is the reference, spec-conformant Apollo Federation v2 composer. There
is no comparable Kotlin-native composer at the time of writing. Cosmo's composer
(`@wundergraph/composition`) is also viable but is currently shipped as Go-first; Node is
ubiquitously available in CI and dev sandboxes, so we wire to it directly.

## Prerequisites

- JDK 21 (or run via `gradle:8.7-jdk21` Docker image)
- Node 18+ with `npm`
- All five subgraphs reachable on the URLs in `composer-config.yaml`
  (default: `localhost:8081-8085`)

## Local usage

```bash
cd schema-registry/composer

# install the @apollo/composition runtime (one-time, ~25 MB):
npm install --omit=dev

# fetch SDLs and compose:
gradle run --args="composer-config.yaml"
```

The composed SDL lands at `schema-registry/output/supergraph.graphql`.

## Failure modes verified by acceptance tests

| Scenario | Behaviour |
| --- | --- |
| All 5 subgraphs federation-correct | Exit 0, supergraph SDL written |
| Subgraph unreachable / wrong URL | Exit 2, error printed to stderr |
| Two subgraphs claiming the same field on the same type | Exit 1, `[error] composition failed` lines listed (e.g. `OVERRIDE_SOURCE_HAS_OVERRIDE`, `INVALID_FIELD_SHARING`, `EXTERNAL_TYPE_MISMATCH`) |
| Subgraph SDL is syntactically invalid | Exit 1, parse-error message |

To verify the duplicate-field-claim path locally, hand-edit one of the subgraph schemas to
contribute a field already owned elsewhere (e.g. add `@KeyDirective` + an already-owned
`name: String!` field on the Property stub in `services/pricing-service`) and re-run the
composer.

## Docker

```bash
docker build -t travelgraph-composer schema-registry/composer
docker run --rm \
  -v "$(pwd)/schema-registry/output:/opt/composer/output" \
  -e PROPERTY_SERVICE_URL=http://host.docker.internal:8081/graphql \
  travelgraph-composer
```

## Files

- `composer-config.yaml` -- subgraph names + URLs
- `scripts/compose.js` -- Node side; the only place `@apollo/composition` is consumed
- `package.json` -- pins `@apollo/composition` and `graphql`
- `src/main/kotlin/com/travelgraph/composer/` -- Kotlin CLI:
  - `Main.kt` -- entry point and orchestration
  - `Config.kt` -- YAML config + env var expansion
  - `SdlFetcher.kt` -- HTTP fetch of `_service.sdl`
  - `Composer.kt` -- Node subprocess driver
