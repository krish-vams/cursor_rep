# TravelGraph Platform — Project Context

## What this is
A federated GraphQL platform for travel services. **Not a booking app — the platform layer behind a travel API ecosystem.** Built to demonstrate platform-engineering depth for a **Software Development Engineer II — GraphQL Platform** role.

## Core idea
Multiple backend teams own different domain services (Property, Pricing, Booking, User, Review). A central Rust-based federated router exposes one unified GraphQL endpoint to clients while routing entity-resolved queries to the right subgraphs. The platform also provides schema governance, observability, CI/CD validation, Redis caching, Kubernetes deployment, and AI-assisted schema review.

## Architecture

**Federated GraphQL Router** — Rust + Tokio + Axum + Reqwest + OpenTelemetry SDK + GraphQL parser crate.
Pipeline (in order): JWT auth → persisted query lookup → parse + validate → depth + cost limits → per-client rate limit → query planner against composed supergraph → subgraph execution with `_entities` resolution over HTTP/2 connection pool → response merge → Redis response cache → field usage tracker → OTel emit. Reliability layer (timeout, retry, circuit breaker, graceful degradation) wraps every subgraph call.

**Subgraph services** — Kotlin + Spring Boot + graphql-kotlin + PostgreSQL + Flyway + Micrometer.
Five services: Property, Pricing, Booking, User, Review. Each declares federation `@key` directives, owns its entities, implements `_entities` resolver, and uses DataLoader for N+1 prevention. Pricing and Review **extend** `Property` rather than the router knowing ownership. Booking mutations use idempotency keys. Mutation responses use payload unions (errors-as-data).

**Schema platform** — Kotlin service + Python/Node AI assistant.
Schema registry (stores subgraph schemas + versions), supergraph composer (build step, fails on conflicts), linter + breaking-change checker (deterministic, blocks CI), field usage analytics (per client + field, drives safe deprecation), AI schema review assistant (advisory, never blocking).

**Observability** — OpenTelemetry collector → Prometheus (metrics) + Grafana (dashboards) + Jaeger (distributed traces). Structured JSON logs. Every signal tagged with client name + version + trace ID + operation name.

**CI/CD** — GitHub Actions. Pipeline: lint → breaking-change check → supergraph compose → AI review (advisory) → tests → Docker build → manifest validation → deploy.

**Runtime** — Docker Compose locally; Kubernetes (optionally EKS) for prod. k6 for load testing.

## Non-negotiables (must be in MVP)
Real Apollo Federation v2 spec (`@key`, `_entities`, supergraph composition) — not a hand-coded gateway. DataLoader in every subgraph. JWT auth at router edge with identity propagation to subgraphs. Persisted queries (clients send hashes, not query text). Per-field usage tracking tagged by client. Required client identification headers (`apollographql-client-name`, `apollographql-client-version`). Mutation payload unions. Schema linter alongside breaking-change check in CI. Introspection disabled in production.

## Out of scope (mention in README, do not build)
Subscriptions (federation + subscriptions is genuinely hard — scoped out). Multi-region routing. Multi-tenancy. Schema contracts / variants. Operation allowlisting beyond what persisted queries provide.

## Tech stack
- **Router:** Rust, Tokio, Axum, Reqwest, OpenTelemetry Rust SDK, GraphQL parser
- **Subgraphs:** Kotlin, Spring Boot, graphql-kotlin, PostgreSQL, Flyway, Micrometer
- **Schema platform:** Kotlin/Spring Boot for registry + composer + checker; Python or Node.js for AI assistant
- **Caching:** Redis (response cache + entity cache)
- **Infra:** Docker, Docker Compose, Kubernetes, Helm (optional), GitHub Actions
- **Observability:** OpenTelemetry, Prometheus, Grafana, Jaeger
- **Load testing:** k6

## MVP phase order
1. Subgraph services with PostgreSQL and per-service GraphQL schemas (no federation yet)
2. Router with parse, validate, basic routing, response merge
3. Federation v2 — add `@key`, supergraph composer, entity resolution
4. Reliability features + Redis caching + query cost controls
5. Auth + persisted queries
6. Observability stack (OTel, Prometheus, Grafana, Jaeger)
7. Schema registry + breaking-change checker + linter + field usage analytics
8. CI/CD pipeline (GitHub Actions)
9. Kubernetes deployment manifests
10. AI schema review assistant
11. k6 load testing with **real** numbers (do not invent metrics)

## Repository strategy
**Single monorepo:** `travelgraph-platform/`. Reasons: supergraph composition needs all subgraph schemas in one place; coordinated schema changes can ship in one PR; single CI/CD config; easier local dev (`docker-compose up` from root); demo-scale project with one contributor.

Per-service boundaries are still clean inside the monorepo (own Dockerfile, own database, own deploy manifest) so the production deployment story is identical to multi-repo. If asked in interview: "monorepo for this project's scope; in production with multiple owning teams, each subgraph would live in its own repo and the schema registry would be the integration point."

## Repository structure
```
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

## Interview positioning (one-liner)
"TravelGraph Platform is a federated GraphQL platform with a Rust-based router, Kotlin subgraph services using Apollo Federation v2, schema governance with breaking-change detection and field-level usage analytics, full observability, persisted queries, JWT auth, CI/CD, Kubernetes deployment, and an AI schema review assistant. The focus is the platform layer — how multiple teams safely evolve schemas, how the router protects performance, how failures are isolated."

## Hard rules for any work on this project
- Federation = Apollo Federation v2 spec adherence, not hand-coded routing
- DataLoader is mandatory in every subgraph
- AI assistant is advisory only — deterministic checks block CI
- Don't invent load test numbers — measure them
- Position as platform engineering, never as a booking app
