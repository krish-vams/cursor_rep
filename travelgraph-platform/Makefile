# TravelGraph Platform — top-level operator targets.
#
# Usage:
#   make help              # this list
#   make up                # bring up infra + all 5 subgraphs (with `subgraphs` profile)
#   make up-infra          # postgres + redis only
#   make down              # stop everything (preserves volumes)
#   make logs              # follow logs from every running service
#   make logs SVC=name     # follow logs from a single service (e.g. SVC=property-service)
#   make seed              # verify all subgraph seed data is loaded
#   make seed-reset        # destructive: drop the postgres volume then re-seed via Flyway
#   make test-subgraphs    # curl smoke test against every subgraph's /graphql
#   make ps                # list running services
#   make build             # build all subgraph images without starting them

SHELL := /usr/bin/env bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

# Detect Docker Compose CLI. Modern Docker Desktop ships the v2 plugin invoked as
# `docker compose` (two words); older installs have the legacy `docker-compose` v1
# binary. We prefer v2 when both exist. Override with `make COMPOSE=docker-compose`
# (or any other invocation) if needed.
COMPOSE ?= $(shell \
    if docker compose version >/dev/null 2>&1; then echo "docker compose"; \
    elif command -v docker-compose >/dev/null 2>&1; then echo "docker-compose"; \
    else echo "docker compose"; fi)
COMPOSE_ALL    := $(COMPOSE) --profile subgraphs
COMPOSE_FULL   := $(COMPOSE) --profile subgraphs --profile router
SCRIPTS_DIR    := scripts

SUBGRAPHS := property-service pricing-service booking-service user-service review-service

# Pretty colours when stdout is a tty.
ifneq (,$(findstring xterm,$(TERM)))
  GREEN := \033[32m
  YEL   := \033[33m
  NC    := \033[0m
else
  GREEN :=
  YEL   :=
  NC    :=
endif

.PHONY: help
help: ## Show this help.
	@printf "$(GREEN)TravelGraph Platform — make targets$(NC)\n"
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | \
	    awk 'BEGIN {FS = ":.*?## "}; {printf "  $(YEL)%-18s$(NC) %s\n", $$1, $$2}'

.PHONY: up
up: ## Bring up postgres, redis, and all 5 subgraph services (subgraphs profile).
	$(COMPOSE_ALL) up --build -d
	@printf "\n$(GREEN)Stack is starting. Run 'make logs' to follow output, or 'make test-subgraphs' once everything is healthy.$(NC)\n"

.PHONY: up-infra
up-infra: ## Bring up only postgres + redis (no subgraphs).
	$(COMPOSE) up -d postgres redis

.PHONY: up-all
up-all: ## Bring up everything: postgres + redis + 5 subgraphs + router.
	$(COMPOSE_FULL) up --build -d
	@printf "\n$(GREEN)Full stack starting. Router will be at http://localhost:8080/graphql once subgraphs are healthy.$(NC)\n"

.PHONY: up-router
up-router: up-all ## Alias for up-all.

.PHONY: down
down: ## Stop and remove containers; volumes are preserved.
	$(COMPOSE_FULL) down

.PHONY: down-volumes
down-volumes: ## Stop containers AND remove the postgres data volume (destructive).
	$(COMPOSE_FULL) down --volumes

.PHONY: ps
ps: ## List service status.
	$(COMPOSE_FULL) ps

.PHONY: logs
logs: ## Follow logs. Use `make logs SVC=property-service` to scope to one service.
	@if [ -n "$(SVC)" ]; then \
	  $(COMPOSE_FULL) logs -f $(SVC); \
	else \
	  $(COMPOSE_FULL) logs -f; \
	fi

.PHONY: build
build: ## Build all subgraph + router images without starting them.
	$(COMPOSE_FULL) build

.PHONY: test-subgraphs
test-subgraphs: ## curl-based smoke test that hits every subgraph's /graphql endpoint.
	@bash $(SCRIPTS_DIR)/smoke-test-subgraphs.sh

.PHONY: test-router
test-router: ## curl-based smoke test against the router (/health + a federated query).
	@bash $(SCRIPTS_DIR)/smoke-test-router.sh

.PHONY: doctor
doctor: ## Verify required local tooling (docker, node, curl) is on PATH.
	@printf "$(GREEN)Checking local tooling...$(NC)\n"
	@if ! command -v docker >/dev/null; then \
	    printf "  $(YEL)docker not found$(NC) -- install Docker Desktop or the docker CLI\n"; exit 1; \
	else printf "  docker:   %s\n" "$$(docker --version)"; fi
	@if docker compose version >/dev/null 2>&1; then \
	    printf "  compose:  %s (v2 plugin)\n" "$$(docker compose version --short 2>/dev/null)"; \
	elif command -v docker-compose >/dev/null; then \
	    printf "  compose:  %s (legacy v1)\n" "$$(docker-compose --version)"; \
	else \
	    printf "  $(YEL)Neither 'docker compose' (v2 plugin) nor 'docker-compose' (v1) is available.$(NC)\n"; \
	    printf "  $(YEL)Install Docker Desktop, or 'brew install docker-compose' on macOS.$(NC)\n"; exit 1; \
	fi
	@if ! command -v node >/dev/null; then \
	    printf "  $(YEL)node not found$(NC) -- composer needs Node 18+ (\`make compose\` will fail)\n"; \
	else printf "  node:     %s\n" "$$(node --version)"; fi
	@if ! command -v curl >/dev/null; then \
	    printf "  $(YEL)curl not found$(NC) -- needed by smoke-test scripts and \`make boot\`\n"; \
	else printf "  curl:     %s\n" "$$(curl --version | head -n1)"; fi
	@printf "$(GREEN)Tooling looks good. Use 'make boot' to start the stack.$(NC)\n"

.PHONY: boot
boot: ## End-to-end Phase 3 bring-up: subgraphs -> wait healthy -> compose -> router.
	@$(MAKE) -s doctor
	@printf "$(GREEN)1/4 starting infra + subgraphs...$(NC)\n"
	@$(MAKE) -s up
	@printf "$(GREEN)2/4 waiting for subgraphs to become reachable...$(NC)\n"
	@for port in 8081 8082 8083 8084 8085; do \
	    for i in $$(seq 1 60); do \
	        if curl --silent --output /dev/null --max-time 2 \
	            --header 'Content-Type: application/json' \
	            --data '{"query":"{ _service { sdl } }"}' \
	            "http://localhost:$$port/graphql"; then \
	            printf "  port %s ready\n" "$$port"; break; \
	        fi; \
	        sleep 1; \
	        if [ "$$i" = "60" ]; then printf "$(YEL)  port %s not ready in 60s$(NC)\n" "$$port"; exit 1; fi; \
	    done; \
	done
	@printf "$(GREEN)3/4 composing supergraph SDL...$(NC)\n"
	@$(MAKE) -s compose
	@printf "$(GREEN)4/4 starting router...$(NC)\n"
	@$(MAKE) -s up-all
	@printf "$(GREEN)Boot complete. Try: make test-router$(NC)\n"

# ----------------------------------------------------------------------------
# Phase 3.2 supergraph composition.
# ----------------------------------------------------------------------------

COMPOSER_DIR := schema-registry/composer
SUPERGRAPH_OUTPUT := schema-registry/output/supergraph.graphql
ROUTER_SUPERGRAPH := router/config/supergraph.graphql

.PHONY: compose
compose: ## Fetch SDLs from the running subgraphs and compose `supergraph.graphql`.
	@printf "$(GREEN)Composing supergraph SDL...$(NC)\n"
	@if ! command -v node >/dev/null; then \
	    printf "$(YEL)Node 18+ is required for the composer (uses @apollo/composition).$(NC)\n"; exit 1; \
	fi
	@if [ ! -d "$(COMPOSER_DIR)/node_modules" ]; then \
	    printf "$(YEL)Installing @apollo/composition (one-time)...$(NC)\n"; \
	    cd $(COMPOSER_DIR) && npm install --omit=dev --no-audit --no-fund; \
	fi
	@cd $(COMPOSER_DIR) && gradle --no-daemon run --args="composer-config.yaml" -q
	@cp $(SUPERGRAPH_OUTPUT) $(ROUTER_SUPERGRAPH)
	@printf "$(GREEN)Wrote $(SUPERGRAPH_OUTPUT) and $(ROUTER_SUPERGRAPH)$(NC)\n"

.PHONY: compose-docker
compose-docker: ## Run the composer inside docker (requires `make up` for the subgraphs).
	docker build -t travelgraph-composer $(COMPOSER_DIR)
	docker run --rm \
	    --network host \
	    -e PROPERTY_SERVICE_URL=http://localhost:8081/graphql \
	    -e PRICING_SERVICE_URL=http://localhost:8082/graphql \
	    -e BOOKING_SERVICE_URL=http://localhost:8083/graphql \
	    -e USER_SERVICE_URL=http://localhost:8084/graphql \
	    -e REVIEW_SERVICE_URL=http://localhost:8085/graphql \
	    -v "$(PWD)/$(COMPOSER_DIR)/composer-config.yaml:/opt/composer/composer-config.yaml" \
	    -v "$(PWD)/schema-registry/output:/opt/composer/schema-registry/output" \
	    travelgraph-composer
	@cp $(SUPERGRAPH_OUTPUT) $(ROUTER_SUPERGRAPH)
	@printf "$(GREEN)Composed via docker. Output written to $(ROUTER_SUPERGRAPH).$(NC)\n"

# ----------------------------------------------------------------------------
# Seed targets.
#
# Seed data ships inside each service's Flyway migration (V1__*.sql), so it
# loads automatically when the postgres volume is fresh and the subgraphs run.
# `make seed` confirms data is present without making any changes; if a table
# is empty it tells you to run `make seed-reset`.
# ----------------------------------------------------------------------------

# Tables expected to be populated, in `schema:table:min_rows` form.
SEED_EXPECTATIONS := \
  property_schema:properties:20 \
  pricing_schema:prices:20 \
  user_schema:users:10 \
  review_schema:reviews:50

.PHONY: seed
seed: ## Verify seed data is loaded in every subgraph's schema.
	@printf "$(GREEN)Verifying seed data across all schemas...$(NC)\n"
	@failed=0; \
	for spec in $(SEED_EXPECTATIONS); do \
	    schema=$$(echo $$spec | cut -d: -f1); \
	    table=$$(echo  $$spec | cut -d: -f2); \
	    expected=$$(echo $$spec | cut -d: -f3); \
	    actual=$$($(COMPOSE) exec -T postgres psql -U postgres -d travelgraph -tAc "SELECT count(*) FROM $$schema.$$table" 2>/dev/null || echo "ERR"); \
	    if [ "$$actual" = "ERR" ]; then \
	        printf "  %-30s $(YEL)NOT READY$(NC) (table missing or postgres down)\n" "$$schema.$$table"; \
	        failed=1; \
	    elif [ "$$actual" -ge "$$expected" ]; then \
	        printf "  %-30s rows=%s (expected >= %s) OK\n" "$$schema.$$table" "$$actual" "$$expected"; \
	    else \
	        printf "  %-30s rows=%s (expected >= %s) $(YEL)UNDER-SEEDED$(NC)\n" "$$schema.$$table" "$$actual" "$$expected"; \
	        failed=1; \
	    fi; \
	done; \
	if [ "$$failed" = "1" ]; then \
	    printf "\n$(YEL)Seed verification failed. If you have just brought the stack up, give it a few seconds for Flyway to run, or use 'make seed-reset' to start clean.$(NC)\n"; \
	    exit 1; \
	fi
	@printf "$(GREEN)All schemas are seeded.$(NC)\n"

.PHONY: seed-reset
seed-reset: ## DESTRUCTIVE: drop postgres volume + re-run Flyway via service restart.
	@printf "$(YEL)This will DROP the postgres volume and re-seed from scratch.$(NC)\n"
	$(COMPOSE_ALL) down --volumes
	$(MAKE) up
