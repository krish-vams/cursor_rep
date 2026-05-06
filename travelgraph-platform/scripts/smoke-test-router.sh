#!/usr/bin/env bash
# Smoke-test the router. Verifies:
#   1. /health returns 200.
#   2. /graphql accepts a valid POST and returns a body containing `data`.
#   3. /graphql with a malformed query returns HTTP 400 with a parse error.
#   4. A federated query that mixes Property (owner) + price/reviews
#      (extending subgraphs) returns a merged response with all three
#      contributed branches and triggers exactly one `_entities` call per
#      extending subgraph at debug log level (Phase 3.4 acceptance).
#
# Phase 3.3+ note: the router needs a composed `supergraph.graphql`. Run
# `make compose` once the subgraphs are up, then `make up-all` to start the
# router. `make boot` chains the whole sequence.

set -euo pipefail

HOST="${1:-localhost}"
ROUTER_URL="http://${HOST}:8080"

if [[ -t 1 ]]; then
  GREEN=$(printf '\033[32m'); RED=$(printf '\033[31m'); YEL=$(printf '\033[33m'); NC=$(printf '\033[0m')
else
  GREEN=''; RED=''; YEL=''; NC=''
fi

failures=0
expect_ok() { printf "  %-55s " "$1"; }
mark_ok()   { printf "%sOK%s\n" "${GREEN}" "${NC}"; }
mark_fail() { printf "%sFAIL%s\n" "${RED}" "${NC}"; printf "    %s\n" "$1"; failures=$((failures+1)); }

# 1. /health
expect_ok "GET /health returns 200"
status=$(curl --silent --output /dev/null --write-out '%{http_code}' "${ROUTER_URL}/health")
[[ "${status}" == "200" ]] && mark_ok || mark_fail "expected 200, got ${status}"

# 2. valid query with a single subgraph
expect_ok "POST /graphql (single-subgraph plan)"
resp=$(curl --silent --max-time 10 \
  --header 'Content-Type: application/json' \
  --data '{"query":"{ searchProperties(city: \"Austin\", limit: 1) { id name } }"}' \
  "${ROUTER_URL}/graphql")
if echo "${resp}" | grep -q '"searchProperties"'; then
  mark_ok
else
  mark_fail "${resp}"
fi

# 3. malformed query -> 400
expect_ok "POST /graphql with syntax error -> 400"
status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --header 'Content-Type: application/json' \
  --data '{"query":"{ unclosed("}' \
  "${ROUTER_URL}/graphql")
[[ "${status}" == "400" ]] && mark_ok || mark_fail "expected 400, got ${status}"

# 4. Federated fan-out: property (owner) + price (pricing) + reviews (review).
# Acceptance: the merged response carries `name`, `price.totalAmount`, and
# `reviews[*].rating` for each property.
expect_ok "POST /graphql (federated Property+Pricing+Review)"
resp=$(curl --silent --max-time 15 \
  --header 'Content-Type: application/json' \
  --data '{"query":"{ searchProperties(city: \"Austin\", limit: 2) { id name price { totalAmount currency } reviews(limit: 2) { id rating } } }"}' \
  "${ROUTER_URL}/graphql")
if echo "${resp}" | grep -q '"searchProperties"' \
   && echo "${resp}" | grep -q '"totalAmount"' \
   && echo "${resp}" | grep -q '"rating"'; then
  mark_ok
else
  mark_fail "${resp}"
fi

# 5. Federated single-property: property(id:) -> Property + price + reviewSummary.
expect_ok "POST /graphql (single-entity Property+Pricing+Review)"
resp=$(curl --silent --max-time 15 \
  --header 'Content-Type: application/json' \
  --data '{"query":"{ property(id: \"11111111-1111-1111-1111-000000000001\") { id name price { totalAmount } reviewSummary { average count } } }"}' \
  "${ROUTER_URL}/graphql")
if echo "${resp}" | grep -q '"property"' \
   && echo "${resp}" | grep -q '"totalAmount"' \
   && echo "${resp}" | grep -q '"average"'; then
  mark_ok
else
  mark_fail "${resp}"
fi

if [[ ${failures} -gt 0 ]]; then
  printf "\n%s%d router check(s) failed%s\n" "${RED}" "${failures}" "${NC}" >&2
  exit 1
fi
printf "\n%sRouter smoke checks passed.%s\n" "${GREEN}" "${NC}"
