#!/usr/bin/env bash
# Phase 2 smoke tests for the TravelGraph router.
#
# Verifies:
#   1. GET /health returns 200.
#   2. POST /graphql with a syntax error returns HTTP 400 (parse path).
#   3. POST /graphql with a minimal well-formed query returns 200 + JSON body with `data`.
#
# Phase 3 adds federated searchProperties / price / reviews checks — see the Phase 3
# commit which replaces this script and requires `make compose` first.

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

expect_ok "GET /health returns 200"
status=$(curl --silent --output /dev/null --write-out '%{http_code}' "${ROUTER_URL}/health")
[[ "${status}" == "200" ]] && mark_ok || mark_fail "expected 200, got ${status}"

expect_ok "POST /graphql with syntax error -> 400"
status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --header 'Content-Type: application/json' \
  --data '{"query":"{ unclosed("}' \
  "${ROUTER_URL}/graphql")
[[ "${status}" == "400" ]] && mark_ok || mark_fail "expected 400, got ${status}"

expect_ok "POST /graphql minimal query -> 200 with data"
resp=$(curl --silent --max-time 10 \
  --header 'Content-Type: application/json' \
  --data '{"query":"{ __typename }"}' \
  "${ROUTER_URL}/graphql")
if echo "${resp}" | grep -q '"data"' && echo "${resp}" | grep -q '__typename'; then
  mark_ok
else
  mark_fail "${resp}"
fi

if [[ ${failures} -gt 0 ]]; then
  printf "\n%s%d router check(s) failed%s\n" "${RED}" "${failures}" "${NC}" >&2
  exit 1
fi
printf "\n%sPhase 2 router smoke checks passed.%s\n" "${GREEN}" "${NC}"
