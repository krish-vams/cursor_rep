# TravelGraph Platform

A GraphQL platform for travel services — a Rust-based gateway router fronting Kotlin subgraphs (Property, Pricing, Booking, User, Review), evolving toward **Apollo Federation v2**, plus schema governance (registry, breaking-change checks, linter, field-usage analytics), OpenTelemetry observability, persisted queries, JWT auth, Redis caching, CI/CD, and Kubernetes manifests. The focus is the **platform layer** — how multiple teams safely evolve schemas and isolate failures — not a booking app.

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

Federation (`@key`, `_entities`, supergraph composition) is **not** enabled in Phase 1 — that
arrives in Phase 3. Per `Context.md` the Phase 1 schemas are intentionally non-federated so
each service stands alone.

## Running the router (Phase 2)

The Rust router (`travelgraph-platform/router/`) is a Tokio + Axum binary that:

1. Parses every inbound GraphQL document with `apollo-parser` (line/column-accurate errors).
2. Runs structural validations: at-least-one-operation, no-anonymous-when-multiple,
   and declared-variables-cover-all-references.
3. Loads subgraph URLs and timeouts from `router/config/subgraphs.yaml`.

Phase 3 layers Apollo Federation v2 on top (composed supergraph SDL, query planner, batched
`_entities`). **This Phase 2 commit** introduces the router crate and wires it into Docker
Compose; the container image ships a minimal `config/supergraph.graphql` placeholder so the
process boots — wait until Phase 3 (`make compose`) before expecting cross-subgraph field
plans against live subgraph SDLs.

```bash
make up-all          # postgres + redis + all 5 subgraphs + router
make test-router     # /health + parse validation + minimal GraphQL smoke
docker compose --profile subgraphs --profile router up   # raw Compose equivalent
```

Configuration is sourced from `router/config/subgraphs.yaml`. URLs support `${ENV:-default}`
substitution so the same file works in Compose (service hostnames) and locally.

| Service | Port  | Endpoint                          |
| ------- | ----- | --------------------------------- |
| router  | 8080  | http://localhost:8080/graphql     |

## Status

Phase 0 — Foundation, Phase 1 — Subgraph Services, Phase 2 — Router Foundation.
See `Context.md` (project root) for the full MVP phase order and non-negotiables.
