#!/usr/bin/env bash
# Smoke-test each subgraph's /graphql endpoint with a representative query.
# Exits non-zero on the first failure (failed connection, non-200 status, or
# the response body containing a top-level `errors` array).
#
# Each query is intentionally:
#   - Read-only (no DB writes -- safe to run repeatedly).
#   - Self-contained (no shared variables between requests).
#   - Hits at least one DataLoader so a passing run also exercises that code path.
#
# Usage: scripts/smoke-test-subgraphs.sh [host]
#   host defaults to localhost. Override for remote/k8s smoke tests.

set -euo pipefail

HOST="${1:-localhost}"

# Colours -- only when stdout is a tty.
if [[ -t 1 ]]; then
  GREEN=$(printf '\033[32m'); RED=$(printf '\033[31m'); YEL=$(printf '\033[33m'); NC=$(printf '\033[0m')
else
  GREEN=''; RED=''; YEL=''; NC=''
fi

# ----------------------------------------------------------------------------
# Each entry: name|port|GraphQL document
# Property UUID 11111111-1111-1111-1111-000000000001 is the Driskill (Austin).
# User UUID     33333333-3333-3333-3333-000000000001 is Avery Patel (PLATINUM).
# ----------------------------------------------------------------------------
QUERIES=(
  'property-service|8081|{ searchProperties(city: \"Austin\", limit: 1) { id name rating } }'
  'pricing-service|8082|{ price(propertyId: \"11111111-1111-1111-1111-000000000001\") { propertyId currency totalAmount nights } }'
  'booking-service|8083|{ availableRooms(propertyId: \"11111111-1111-1111-1111-000000000001\", checkIn: \"2026-06-11\", checkOut: \"2026-06-13\") { id name available } }'
  'user-service|8084|{ user(id: \"33333333-3333-3333-3333-000000000001\") { id name loyaltyStatus } }'
  'review-service|8085|{ reviewSummary(propertyId: \"11111111-1111-1111-1111-000000000001\") { propertyId average count } }'
)

failures=0
for entry in "${QUERIES[@]}"; do
  IFS='|' read -r name port query <<<"${entry}"
  url="http://${HOST}:${port}/graphql"
  printf "  %-20s -> %s ... " "${name}" "${url}"
  payload=$(printf '{"query":"%s"}' "${query}")
  response=$(curl --silent --show-error --fail --max-time 10 \
    --header 'Content-Type: application/json' \
    --header 'apollographql-client-name: smoke-test' \
    --header 'apollographql-client-version: 0.1.0' \
    --header 'x-user-id: 33333333-3333-3333-3333-000000000001' \
    --data "${payload}" "${url}" 2>&1) || {
      printf "%sFAIL%s\n" "${RED}" "${NC}"
      printf "    %s\n" "${response}"
      failures=$((failures+1))
      continue
    }

  if printf '%s' "${response}" | grep -q '"errors"'; then
    printf "%sFAIL%s (errors in response)\n" "${RED}" "${NC}"
    printf "    %s\n" "${response}"
    failures=$((failures+1))
    continue
  fi

  if ! printf '%s' "${response}" | grep -q '"data"'; then
    printf "%sFAIL%s (no data field)\n" "${YEL}" "${NC}"
    printf "    %s\n" "${response}"
    failures=$((failures+1))
    continue
  fi

  printf "%sOK%s\n" "${GREEN}" "${NC}"
done

if [[ ${failures} -gt 0 ]]; then
  printf "\n%s%d subgraph(s) failed%s\n" "${RED}" "${failures}" "${NC}" >&2
  exit 1
fi

printf "\n%sAll 5 subgraphs responded successfully.%s\n" "${GREEN}" "${NC}"
