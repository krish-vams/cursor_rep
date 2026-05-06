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

COMPOSE        ?= docker-compose
COMPOSE_ALL    := $(COMPOSE) --profile subgraphs
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

.PHONY: down
down: ## Stop and remove containers; volumes are preserved.
	$(COMPOSE_ALL) down

.PHONY: down-volumes
down-volumes: ## Stop containers AND remove the postgres data volume (destructive).
	$(COMPOSE_ALL) down --volumes

.PHONY: ps
ps: ## List service status.
	$(COMPOSE_ALL) ps

.PHONY: logs
logs: ## Follow logs. Use `make logs SVC=property-service` to scope to one service.
	@if [ -n "$(SVC)" ]; then \
	  $(COMPOSE_ALL) logs -f $(SVC); \
	else \
	  $(COMPOSE_ALL) logs -f; \
	fi

.PHONY: build
build: ## Build all subgraph images without starting them.
	$(COMPOSE_ALL) build

.PHONY: test-subgraphs
test-subgraphs: ## curl-based smoke test that hits every subgraph's /graphql endpoint.
	@bash $(SCRIPTS_DIR)/smoke-test-subgraphs.sh

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
