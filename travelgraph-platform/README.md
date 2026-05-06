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

Phase 1 ships three subgraph services on the JVM. Each is a standalone Gradle (Kotlin DSL)
project under `services/<name>-service/` with its own `Dockerfile` (multi-stage, distroless
runtime). All three are wired into `docker-compose.yml` so the simplest path is:

```bash
# Bring up Postgres + Redis + all three subgraphs (builds images on first run).
docker-compose up --build

# Or just one service:
docker-compose up property-service
```

| Service           | Port  | GraphQL endpoint                  | GraphiQL (dev only)                  |
| ----------------- | ----- | --------------------------------- | ------------------------------------ |
| property-service  | 8081  | http://localhost:8081/graphql     | http://localhost:8081/graphiql       |
| pricing-service   | 8082  | http://localhost:8082/graphql     | http://localhost:8082/graphiql       |
| booking-service   | 8083  | http://localhost:8083/graphql     | http://localhost:8083/graphiql       |

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

## Status

Phases 0 and 1 — Foundation + Subgraph Services. See `Context.md` (project root) for the full
MVP phase order and non-negotiables.
