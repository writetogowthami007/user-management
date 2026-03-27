#!/usr/bin/env bash

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
COMPOSE_PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

PASS_COUNT=0
FAIL_COUNT=0

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

GREEN="\033[0;32m"
RED="\033[0;31m"
YELLOW="\033[1;33m"
NC="\033[0m"

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  echo -e "${GREEN}PASS${NC} - $1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  echo -e "${RED}FAIL${NC} - $1"
}

info() {
  echo -e "${YELLOW}INFO${NC} - $1"
}

check_cmd() {
  if command -v "$1" >/dev/null 2>&1; then
    pass "Command '$1' available"
  else
    fail "Command '$1' missing"
  fi
}

check_http_status() {
  local name="$1"
  local url="$2"
  local expected="$3"
  local out_file="$4"
  local code
  code="$(curl -s -o "$out_file" -w "%{http_code}" "$url" 2>/dev/null || true)"
  if [[ "$code" == "$expected" ]]; then
    pass "$name ($expected)"
  else
    fail "$name expected $expected got ${code:-n/a}"
  fi
}

check_contains() {
  local name="$1"
  local file="$2"
  local pattern="$3"
  if grep -Eiq "$pattern" "$file" >/dev/null 2>&1; then
    pass "$name"
  else
    fail "$name (pattern '$pattern' not found)"
  fi
}

wait_http_200() {
  local name="$1"
  local url="$2"
  local attempts="${3:-30}"
  local sleep_sec="${4:-2}"
  local code=""
  local i
  for ((i = 1; i <= attempts; i++)); do
    code="$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || true)"
    if [[ "$code" == "200" ]]; then
      pass "$name became ready"
      return 0
    fi
    sleep "$sleep_sec"
  done
  fail "$name did not become ready (last code: ${code:-n/a})"
  return 1
}

wait_prometheus_target_up() {
  local attempts="${1:-20}"
  local sleep_sec="${2:-3}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    local body
    body="$(curl -s "$PROM_URL/api/v1/targets" 2>/dev/null || true)"
    if echo "$body" | grep -q '"job":"user-management-service"' && echo "$body" | grep -q '"health":"up"'; then
      pass "Prometheus app target became UP"
      echo "$body" > "$TMP_DIR/prom_targets.json"
      return 0
    fi
    sleep "$sleep_sec"
  done
  fail "Prometheus app target did not become UP in time"
  curl -s "$PROM_URL/api/v1/targets" > "$TMP_DIR/prom_targets.json" 2>/dev/null || true
  return 1
}

echo "== user-management-service full verification =="

check_cmd docker
check_cmd curl
check_cmd python3

if ! docker info >/dev/null 2>&1; then
  fail "Docker daemon not reachable (start Colima/Docker Desktop first)"
else
  pass "Docker daemon reachable"
fi

info "Validating docker compose file"
if (cd "$COMPOSE_PROJECT_DIR" && docker compose config >/dev/null 2>&1); then
  pass "docker compose config valid"
else
  fail "docker compose config failed"
fi

info "Starting compose stack"
if (cd "$COMPOSE_PROJECT_DIR" && docker compose up --build -d >/dev/null 2>&1); then
  pass "docker compose up --build -d"
else
  fail "docker compose up failed"
fi

info "Waiting for endpoints to become ready"
wait_http_200 "App health endpoint" "$BASE_URL/actuator/health" 45 2
wait_http_200 "Prometheus ready endpoint" "$PROM_URL/-/ready" 30 2
wait_http_200 "Grafana health endpoint" "$GRAFANA_URL/api/health" 45 2

info "Checking container statuses"
PS_OUT="$TMP_DIR/compose_ps.txt"
(cd "$COMPOSE_PROJECT_DIR" && docker compose ps -a >"$PS_OUT" 2>/dev/null || true)
check_contains "Postgres healthy" "$PS_OUT" "user-management-postgres.*healthy"
check_contains "App container up" "$PS_OUT" "user-management-app.*Up"
check_contains "Prometheus container up" "$PS_OUT" "user-management-prometheus.*Up"
check_contains "Grafana container up" "$PS_OUT" "user-management-grafana.*Up"

info "Running service health checks"
check_http_status "App actuator health" "$BASE_URL/actuator/health" "200" "$TMP_DIR/app_health.json"
check_contains "App health status UP" "$TMP_DIR/app_health.json" "\"status\"\\s*:\\s*\"UP\""

check_http_status "Prometheus ready" "$PROM_URL/-/ready" "200" "$TMP_DIR/prom_ready.txt"
check_contains "Prometheus ready body" "$TMP_DIR/prom_ready.txt" "Ready"

check_http_status "Grafana health" "$GRAFANA_URL/api/health" "200" "$TMP_DIR/grafana_health.json"
check_contains "Grafana DB ok" "$TMP_DIR/grafana_health.json" "\"database\"\\s*:\\s*\"ok\""

check_http_status "Prometheus targets API" "$PROM_URL/api/v1/targets" "200" "$TMP_DIR/prom_targets.json"
wait_prometheus_target_up 20 3

info "Running end-to-end API flow"
TEST_EMAIL="manual.$(date +%s)@example.com"
TEST_PASSWORD="password123"

CREATE_CODE="$(curl -s -o "$TMP_DIR/create.json" -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -d "{\"firstName\":\"Manual\",\"lastName\":\"Tester\",\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASSWORD\"}" \
  "$BASE_URL/api/users" 2>/dev/null || true)"
if [[ "$CREATE_CODE" == "201" ]]; then
  pass "Create user returns 201"
else
  fail "Create user expected 201 got ${CREATE_CODE:-n/a}"
fi
check_contains "Create user response message" "$TMP_DIR/create.json" "\"message\"\\s*:\\s*\"added\""

LOGIN_CODE="$(curl -s -o "$TMP_DIR/login.json" -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASSWORD\"}" \
  "$BASE_URL/api/auth/login" 2>/dev/null || true)"
if [[ "$LOGIN_CODE" == "200" ]]; then
  pass "Login returns 200"
else
  fail "Login expected 200 got ${LOGIN_CODE:-n/a}"
fi
check_contains "Login response has tokenType Bearer" "$TMP_DIR/login.json" "\"tokenType\"\\s*:\\s*\"Bearer\""

TOKEN="$(python3 - <<PY
import json
import sys
try:
    data = json.load(open("$TMP_DIR/login.json"))
    print(data.get("accessToken", ""))
except Exception:
    print("")
PY
)"
if [[ -n "$TOKEN" ]]; then
  pass "JWT accessToken extracted"
else
  fail "JWT accessToken missing"
fi

NOAUTH_CODE="$(curl -s -o "$TMP_DIR/get_noauth.json" -w "%{http_code}" \
  "$BASE_URL/api/users/$TEST_EMAIL" 2>/dev/null || true)"
if [[ "$NOAUTH_CODE" == "401" ]]; then
  pass "Unauthorized GET returns 401"
else
  fail "Unauthorized GET expected 401 got ${NOAUTH_CODE:-n/a}"
fi

AUTH_CODE="$(curl -s -o "$TMP_DIR/get_auth.json" -w "%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/users/$TEST_EMAIL" 2>/dev/null || true)"
if [[ "$AUTH_CODE" == "200" ]]; then
  pass "Authorized GET returns 200"
else
  fail "Authorized GET expected 200 got ${AUTH_CODE:-n/a}"
fi
check_contains "Authorized GET returns expected email" "$TMP_DIR/get_auth.json" "$TEST_EMAIL"

info "Verifying PostgreSQL readiness and persisted user"
if docker exec user-management-postgres pg_isready -U postgres -d user_management >/dev/null 2>&1; then
  pass "PostgreSQL readiness check passed"
else
  fail "PostgreSQL readiness check failed"
fi

if docker exec user-management-postgres psql -U postgres -d user_management -tAc "select count(*) from users where email='$TEST_EMAIL';" >"$TMP_DIR/db_count.txt" 2>/dev/null; then
  COUNT="$(tr -d '[:space:]' < "$TMP_DIR/db_count.txt")"
  if [[ "$COUNT" =~ ^[1-9][0-9]*$ ]]; then
    pass "User persisted in PostgreSQL"
  else
    fail "User not found in PostgreSQL"
  fi
else
  fail "Could not query PostgreSQL users table"
fi

info "Checking correlation ID header behavior"
CORR_HEADERS="$TMP_DIR/corr_headers.txt"
curl -s -D "$CORR_HEADERS" -o /dev/null -H "X-Correlation-Id: test-corr-123" "$BASE_URL/actuator/health" >/dev/null 2>&1 || true
check_contains "Echoes supplied correlation ID" "$CORR_HEADERS" "X-Correlation-Id:\\s*test-corr-123"

AUTO_CORR_HEADERS="$TMP_DIR/auto_corr_headers.txt"
curl -s -D "$AUTO_CORR_HEADERS" -o /dev/null "$BASE_URL/actuator/health" >/dev/null 2>&1 || true
check_contains "Generates correlation ID when missing" "$AUTO_CORR_HEADERS" "X-Correlation-Id:\\s*[0-9a-fA-F-]{8,}"

echo
echo "== Summary =="
echo "PASS: $PASS_COUNT"
echo "FAIL: $FAIL_COUNT"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi

echo "All checks passed."
