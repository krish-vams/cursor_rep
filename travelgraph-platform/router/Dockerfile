# syntax=docker/dockerfile:1.6

# ---- Stage 1: build with cargo-chef-style caching --------------------------
FROM rust:1.85-bookworm AS build
WORKDIR /workspace

# Pre-build dependencies in their own layer so source-only changes don't
# invalidate the dep cache. We do this by copying just the manifests and a
# stub main.rs first.
COPY Cargo.toml ./Cargo.toml
COPY crates/travelgraph-router/Cargo.toml crates/travelgraph-router/Cargo.toml
RUN mkdir -p crates/travelgraph-router/src \
    && echo 'fn main() {}' > crates/travelgraph-router/src/main.rs \
    && cargo build --release -p travelgraph-router \
    && rm -rf crates/travelgraph-router/src

COPY crates/travelgraph-router/src ./crates/travelgraph-router/src
COPY crates/travelgraph-router/tests ./crates/travelgraph-router/tests

# Force a rebuild of just the application by touching main.rs (the dep layer
# is already cached). `cargo build` is idempotent so a repeat run is cheap.
RUN touch crates/travelgraph-router/src/main.rs \
    && cargo build --release -p travelgraph-router

# ---- Stage 2: minimal runtime ----------------------------------------------
# distroless/cc has glibc + libssl + ca-certs, so a dynamically-linked Rust
# binary built on Bookworm runs cleanly. The final image is roughly 25-35 MiB.
FROM gcr.io/distroless/cc-debian12:nonroot AS runtime
WORKDIR /app

COPY --from=build /workspace/target/release/travelgraph-router /usr/local/bin/travelgraph-router
COPY --from=build /workspace/Cargo.toml /app/Cargo.toml
COPY config /app/config

ENV ROUTER_PORT=8080 \
    ROUTER_SUBGRAPH_CONFIG=/app/config/subgraphs.yaml \
    RUST_LOG=info \
    ROUTER_LOG_FORMAT=json

EXPOSE 8080
USER nonroot
ENTRYPOINT ["/usr/local/bin/travelgraph-router"]
