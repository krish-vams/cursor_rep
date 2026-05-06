# TravelGraph Platform

A federated GraphQL platform for travel services — a Rust-based federated router fronting Kotlin subgraphs (Property, Pricing, Booking, User, Review) using **Apollo Federation v2**, with schema governance (registry, breaking-change checks, linter, field-usage analytics), full observability (OpenTelemetry + Prometheus + Grafana + Jaeger), persisted queries, JWT auth, Redis response caching, GitHub Actions CI/CD, and Kubernetes deployment manifests. The focus is the **platform layer** — how multiple teams safely evolve schemas, how the router protects performance, and how failures are isolated — not a booking app.

## Architecture diagram

> Placeholder — diagram to be added (e.g. `docs/architecture.png` or a Mermaid diagram). It should show: clients → router (auth, persisted queries, parse/validate, cost limits, rate limit, query plan, subgraph fetch, merge, cache, telemetry) → subgraphs (Property, Pricing, Booking, User, Review) over their own Postgres databases, with the schema registry feeding the supergraph, and OTel signals flowing to Prometheus/Grafana/Jaeger.

```text
[ clients ] --> [ Rust router ] --> [ subgraphs (Kotlin) ] --> [ Postgres per service ]
                       |                     ^
                       v                     |
                 [ Redis cache ]    [ schema registry / supergraph ]
                       |
                       v
              [ OTel -> Prometheus / Grafana / Jaeger ]
```

## Repository layout

```text
travelgraph-platform/
  router/                     (Rust)
  services/
    property-service/         (Kotlin)
    pricing-service/          (Kotlin)
    booking-service/          (Kotlin)
    user-service/             (Kotlin)
    review-service/           (Kotlin)
  schema-registry/            (Kotlin — registry + composer + linter + checker + usage analytics)
  ai-schema-assistant/        (Python or Node.js)
  observability/              (Prometheus config, Grafana dashboards, alert rules)
  k8s/                        (Kubernetes manifests for all services)
  load-tests/                 (k6 scripts)
  .github/workflows/          (CI/CD pipelines)
  docker-compose.yml
  README.md
```

## Local development quickstart

Start the shared infrastructure (PostgreSQL 16 + Redis 7):

```bash
docker-compose up postgres redis
```

Verify everything is healthy:

```bash
# Redis should reply PONG
redis-cli -h localhost ping

# Postgres should list all five subgraph schemas
psql -h localhost -U postgres -c "\dn"
# Expected schemas: property_schema, pricing_schema, booking_schema,
#                   user_schema, review_schema (plus public + information_schema).
```

Default credentials (local only): user `postgres`, password `postgres`, database `travelgraph`.
Postgres data persists in the named volume `travelgraph-postgres-data`. Redis runs in cache-only
mode (no AOF, no RDB snapshots).

See `docs/` for shared conventions (e.g. `docs/graphql-conventions.md`).

## Running the subgraph services

Phase 1 ships **all five** subgraph services on the JVM. Each is a standalone Gradle
(Kotlin DSL) project under `services/<name>-service/` with its own `Dockerfile` (multi-stage,
distroless runtime). All five are wired into `docker-compose.yml` behind a `subgraphs`
profile, with a top-level `Makefile` for common operator workflows:

```bash
make up              # postgres + redis + all 5 subgraphs (subgraphs profile, --build)
make up-infra        # postgres + redis only
make down            # stop everything (preserves volumes)
make logs            # follow logs from all services
make logs SVC=property-service
make seed            # verify all subgraph seed data is loaded
make seed-reset      # destructive: drop volume and re-seed
make test-subgraphs  # curl smoke test against every /graphql endpoint
make ps              # service status
```

`make help` lists every target. The underlying Compose commands are:

```bash
docker-compose up postgres redis             # infra only
docker-compose --profile subgraphs up        # everything
```

| Service           | Port  | GraphQL endpoint                  | GraphiQL (dev only)                  |
| ----------------- | ----- | --------------------------------- | ------------------------------------ |
| property-service  | 8081  | http://localhost:8081/graphql     | http://localhost:8081/graphiql       |
| pricing-service   | 8082  | http://localhost:8082/graphql     | http://localhost:8082/graphiql       |
| booking-service   | 8083  | http://localhost:8083/graphql     | http://localhost:8083/graphiql       |
| user-service      | 8084  | http://localhost:8084/graphql     | http://localhost:8084/graphiql       |
| review-service    | 8085  | http://localhost:8085/graphql     | http://localhost:8085/graphiql       |

`user-service` reads the caller's identity from the `x-user-id` request header
(temporary stand-in for Phase 5 JWT auth). Pass it explicitly when calling `me`:

```bash
curl http://localhost:8084/graphql \
  -H 'Content-Type: application/json' \
  -H 'x-user-id: 33333333-3333-3333-3333-000000000001' \
  -d '{"query":"{ me { id name loyaltyStatus } }"}'
```

Each service exposes Spring Actuator at `/actuator/health` and Prometheus metrics at
`/actuator/prometheus`. GraphiQL and introspection are gated by `SPRING_PROFILES_ACTIVE=dev`
(the Compose default); production builds run with neither.

### Building locally without Docker

If you have Gradle 8.x and JDK 21 installed locally:

```bash
cd services/property-service && gradle bootRun
```

If you only have a JDK and no Gradle, generate a Gradle wrapper once per service:

```bash
cd services/property-service && gradle wrapper --gradle-version=8.7
./gradlew bootRun
```

Each subgraph is a fully federated v2 graph as of Phase 3 — every service answers
`query { _service { sdl } }`, declares its primary entity with `@key(fields: "id")`, and
implements `_entities(representations: [_Any!]!)` via graphql-kotlin's
`FederatedTypeSuspendResolver`. Pricing and Review do not own `Property`; they extend it
with `price(...)`, `reviews`, and `reviewSummary` so the router can fan-out to them by
entity reference.

## Supergraph composition (Phase 3.2)

The composer at `schema-registry/composer/` is a Kotlin CLI that:

1. POSTs `query { _service { sdl } }` to every subgraph URL in `composer-config.yaml` in
   parallel (configurable timeout, default 5s).
2. Hands the SDLs to `@apollo/composition` (the reference Apollo Federation v2 composer)
   via a thin Node subprocess (`scripts/compose.js`).
3. On success, writes `schema-registry/output/supergraph.graphql` with a banner header.
4. On any composition error (e.g. duplicate field claim across subgraphs) prints every
   error from `@apollo/composition` to stderr and exits non-zero.

```bash
make up                # bring the subgraphs up first (composer queries them live)
make compose           # one-time `npm install` + run the composer
# or run it inside docker:
make compose-docker
```

The composed SDL is also bind-mounted into the router container at startup, so a fresh
compose run is picked up by `docker-compose restart router` (no rebuild needed).

## Running the router (Phase 3 — federated)

The Rust router (`router/`) is a Tokio + Axum binary that:

1. Parses every inbound GraphQL document with `apollo-parser` (line/column-accurate errors).
2. Runs structural validations: at-least-one-operation, no-anonymous-when-multiple,
   and declared-variables-cover-all-references.
3. Reads the composed `supergraph.graphql` at startup and uses it to drive a federated
   **query planner**. Each request is lowered into a tree of `RootFetch` (initial calls
   to owning subgraphs, parallel) and `EntityFetch` (`_entities` round-trips, batched per
   extending subgraph) nodes, then executed.
4. Batches `_entities` calls per extending subgraph: a query that touches N properties
   produces exactly **one** `_entities` call to Pricing and **one** to Review, regardless
   of N (the Phase 3.4 N+1 fix).
5. Emits one structured `request_completed` log line per request with `request_id`,
   `operation_name`, `total_duration_ms`, error count, and a per-subgraph duration map.
   With `RUST_LOG=travelgraph_router=debug` the full execution plan is logged for every
   request.

```bash
make boot            # postgres + redis + 5 subgraphs -> wait healthy -> compose -> router
make test-router     # /health + /graphql smoke checks (single-subgraph + federated fan-out)
```

`make boot` is the recommended end-to-end flow because the supergraph composer requires
the subgraphs to be running. If you want to do it manually:

```bash
make up                                                   # subgraphs
make compose                                              # writes supergraph.graphql
docker-compose --profile subgraphs --profile router up    # add the router
```

Per-subgraph failure modes:

| Upstream behaviour              | Merged response                                    |
| ------------------------------- | -------------------------------------------------- |
| 200 with `errors`               | data merged, errors propagated as-is               |
| 5xx                             | data field set to `null`, `UPSTREAM_UNAVAILABLE`   |
| Timeout (1s default per call)   | data field set to `null`, `UPSTREAM_TIMEOUT`       |
| Connection refused / DNS error  | data field set to `null`, `UPSTREAM_UNAVAILABLE`   |
| Extending subgraph fails        | extending fields set to `null`, root fetch kept   |

Configuration is sourced from `router/config/subgraphs.yaml`. The supergraph SDL provides
field-level routing; the YAML provides per-subgraph URL overrides and timeouts. URLs
support `${ENV:-default}` substitution so the same file works in Compose (where each
subgraph is a service hostname) and in local development.

| Service | Port  | Endpoint                          |
| ------- | ----- | --------------------------------- |
| router  | 8080  | http://localhost:8080/graphql     |

## Status

Phase 0 — Foundation, Phase 1 — Subgraph Services, Phase 2 — Router Foundation,
Phase 3 — Federation v2 (federated subgraphs + composer + query planner + batched entity
resolution). See `Context.md` (project root) for the full MVP phase order and non-negotiables.
