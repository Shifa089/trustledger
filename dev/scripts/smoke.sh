#!/usr/bin/env bash
# Smoke test (M14): is a running TrustLedger deployment basically alive? Run after starting the apps, locally with
# spring-boot:run or from the images (infra/docker-compose.yml, profile "apps"), and later after a deploy.
#
#   scripts/smoke.sh
#
# Steps, stopping at the first failure (exit 1):
#   1. Health: core-app and risk-aml-service /actuator/health are UP (waits up to SMOKE_WAIT seconds for both, so it
#      can run right after "docker compose up"), and Keycloak serves the realm's OIDC discovery document.
#   2. Token: password grant for the sender (customer1) and the recipient (customer2).
#   3. GET /api/v1/me as the sender: 200, prints the account status and roles only (never e-mail or phone).
#   4. One P2P transfer of SMOKE_AMOUNT from the sender to the recipient's wallet: 201 COMPLETED, the sender's
#      available balance drops by exactly that amount, the same Idempotency-Key replays without moving money
#      again, and the recipient can read the transfer.
#
# Prerequisites (fake test users only, docs/aml-demo.md): customer1 and customer2 have FULL KYC (FULL wallets) and
# customer1 has at least SMOKE_AMOUNT available. Needs Git Bash (or any bash) with curl and openssl. Run from dev/.
# Reads SMOKE_PASSWORD, else TRUSTLEDGER_DEV_USER_PASSWORD from the environment or .env; never prints a secret,
# a token or personal data.
#
# Settings (environment):
#   TRUSTLEDGER_API       core-app base URL            (default http://localhost:8080)
#   TRUSTLEDGER_AML_API   risk-aml-service base URL    (default http://localhost:8081)
#   TRUSTLEDGER_KEYCLOAK  Keycloak base URL            (default http://localhost:8180)
#   SMOKE_CLIENT_ID       Keycloak client for the password grant (default trustledger-dev)
#   SMOKE_SENDER / SMOKE_RECIPIENT  test users        (default customer1 / customer2)
#   SMOKE_PASSWORD        their password              (default TRUSTLEDGER_DEV_USER_PASSWORD)
#   Example with the seeded load-test users (load-tests/README.md), whose wallets AML demos never froze:
#     SMOKE_SENDER=perf001 SMOKE_RECIPIENT=perf002 SMOKE_CLIENT_ID=trustledger-perf \
#       SMOKE_PASSWORD="$(sed -n 's/^TRUSTLEDGER_PERF_USER_PASSWORD=//p' .env)" scripts/smoke.sh
#   SMOKE_AMOUNT          rupees, two decimals         (default 1.00)
#   SMOKE_WAIT            seconds to wait for health   (default 120)
# Plain http is accepted only for localhost/127.0.0.1: a password grant over http elsewhere would send the test
# password in clear text.

set -euo pipefail

API="${TRUSTLEDGER_API:-http://localhost:8080}"
AML_API="${TRUSTLEDGER_AML_API:-http://localhost:8081}"
KEYCLOAK="${TRUSTLEDGER_KEYCLOAK:-http://localhost:8180}"
CLIENT_ID="${SMOKE_CLIENT_ID:-trustledger-dev}"
SENDER="${SMOKE_SENDER:-customer1}"
RECIPIENT="${SMOKE_RECIPIENT:-customer2}"
AMOUNT="${SMOKE_AMOUNT:-1.00}"
WAIT_SECONDS="${SMOKE_WAIT:-120}"
ENV_FILE="${TRUSTLEDGER_ENV_FILE:-.env}"

die() { echo "smoke: FAIL: $*" >&2; exit 1; }
ok() { echo "smoke: ok    $*"; }

require_safe_url() {
    [[ "$1" =~ ^https:// || "$1" =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]+)?(/.*)?$ ]] \
        || die "refusing $1: use https, or http only for localhost/127.0.0.1"
}

load_password() {
    PASSWORD="${SMOKE_PASSWORD:-${TRUSTLEDGER_DEV_USER_PASSWORD:-}}"
    if [[ -z "$PASSWORD" && -f "$ENV_FILE" ]]; then
        PASSWORD=$(sed -n 's/^TRUSTLEDGER_DEV_USER_PASSWORD=//p' "$ENV_FILE" | tr -d '\r' | head -n 1)
    fi
    [[ -n "$PASSWORD" ]] || die "no password: set SMOKE_PASSWORD or TRUSTLEDGER_DEV_USER_PASSWORD (or put it in $ENV_FILE)"
}

# String value of a JSON field; enough for our own flat responses (no jq on Git Bash). Whitespace around the colon
# is allowed: an idempotent replay returns the body stored as JSONB, which PostgreSQL prints as "id": "...".
json_field() {
    sed -nE "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"([^\"]*)\".*/\1/p" <<<"$2" | head -n 1
}

# "46000.00" -> 4600000 (paise). Integer arithmetic only, never floating point.
to_paise() {
    [[ "$1" =~ ^([0-9]+)\.([0-9]{2})$ ]] || die "unexpected amount format: '$1'"
    echo $(( 10#${BASH_REMATCH[1]} * 100 + 10#${BASH_REMATCH[2]} ))
}

uuid4() {
    local h
    h=$(openssl rand -hex 16)
    printf '%s-%s-4%s-%x%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" $(( 0x${h:16:1} & 0x3 | 0x8 )) "${h:17:3}" "${h:20:12}"
}

# Sets globals STATUS, HEADERS and BODY. Arguments: curl options and the URL.
http() {
    local out headers_file
    headers_file=$(mktemp)
    out=$(curl -sS --max-time 15 -D "$headers_file" -w '\n%{http_code}' "$@") || { rm -f "$headers_file"; return 1; }
    STATUS="${out##*$'\n'}"
    BODY="${out%$'\n'*}"
    HEADERS=$(tr -d '\r' <"$headers_file")
    rm -f "$headers_file"
}

wait_for_health() {
    local name="$1" url="$2" deadline=$(( SECONDS + WAIT_SECONDS ))
    while :; do
        if http "$url/actuator/health" 2>/dev/null && [[ "$STATUS" == "200" && "$(json_field status "$BODY")" == "UP" ]]; then
            ok "$name health UP ($url)"
            return
        fi
        (( SECONDS < deadline )) || die "$name not healthy after ${WAIT_SECONDS}s ($url/actuator/health -> ${STATUS:-no answer})"
        sleep 3
    done
}

token_for() {
    http "$KEYCLOAK/realms/trustledger/protocol/openid-connect/token" \
        -d grant_type=password -d "client_id=$CLIENT_ID" -d "username=$1" \
        --data-urlencode "password=$PASSWORD" || die "Keycloak not reachable"
    local token
    token=$(json_field access_token "$BODY")
    [[ "$STATUS" == "200" && -n "$token" ]] || die "no token for $1 (HTTP $STATUS; user, password or client wrong?)"
    printf '%s' "$token"
}

available_of() {
    http "$API/api/v1/wallets/me" -H "Authorization: Bearer $1" || die "core-app not reachable"
    [[ "$STATUS" == "200" ]] || die "GET /api/v1/wallets/me -> HTTP $STATUS: $BODY"
    to_paise "$(json_field available "$BODY")"
}

main() {
    for url in "$API" "$AML_API" "$KEYCLOAK"; do require_safe_url "$url"; done
    [[ "$AMOUNT" =~ ^[0-9]+\.[0-9]{2}$ ]] || die "SMOKE_AMOUNT must look like 1.00 (got $AMOUNT)"
    load_password

    # 1. Health
    wait_for_health core-app "$API"
    wait_for_health risk-aml-service "$AML_API"
    http "$KEYCLOAK/realms/trustledger/.well-known/openid-configuration" || die "Keycloak not reachable"
    [[ "$STATUS" == "200" ]] || die "Keycloak realm discovery -> HTTP $STATUS"
    ok "Keycloak realm trustledger discoverable"

    # 2. Tokens
    local sender_token recipient_token
    sender_token=$(token_for "$SENDER")
    recipient_token=$(token_for "$RECIPIENT")
    ok "tokens for $SENDER and $RECIPIENT"

    # 3. /me (status and roles only: the body also holds e-mail and phone)
    http "$API/api/v1/me" -H "Authorization: Bearer $sender_token" || die "core-app not reachable"
    [[ "$STATUS" == "200" ]] || die "GET /api/v1/me -> HTTP $STATUS"
    local roles
    roles=$(sed -nE 's/.*"roles":\[([^]]*)\].*/\1/p' <<<"$BODY" | tr -d '"')
    ok "GET /api/v1/me as $SENDER: status $(json_field status "$BODY"), roles [$roles]"

    # 4. One transfer
    http "$API/api/v1/wallets/me" -H "Authorization: Bearer $recipient_token" || die "core-app not reachable"
    [[ "$STATUS" == "200" ]] || die "GET /api/v1/wallets/me as $RECIPIENT -> HTTP $STATUS: $BODY"
    local recipient_wallet amount_paise before after
    recipient_wallet=$(json_field walletId "$BODY")
    amount_paise=$(to_paise "$AMOUNT")
    before=$(available_of "$sender_token")
    (( before >= amount_paise )) \
        || die "$SENDER has less than $AMOUNT available; top it up first (docs/aml-demo.md)"

    local key body transfer_id
    key=$(uuid4)
    body=$(printf '{"recipientWalletId":"%s","amount":"%s","note":"smoke test"}' "$recipient_wallet" "$AMOUNT")
    http -X POST "$API/api/v1/transfers" -H "Authorization: Bearer $sender_token" \
        -H "Idempotency-Key: $key" -H 'Content-Type: application/json' --data-binary "$body" \
        || die "core-app not reachable"
    if [[ "$STATUS" != "201" ]]; then
        [[ "$BODY" != *WALLET_FROZEN* ]] \
            || echo "smoke: hint: an AML demo froze this wallet; use other users (SMOKE_SENDER, see the header)" >&2
        die "POST /api/v1/transfers -> HTTP $STATUS: $BODY"
    fi
    [[ "$(json_field status "$BODY")" == "COMPLETED" ]] || die "transfer not COMPLETED: $BODY"
    transfer_id=$(json_field id "$BODY")
    after=$(available_of "$sender_token")
    (( before - after == amount_paise )) \
        || die "sender's available balance changed by $(( before - after )) paise, expected $amount_paise"
    ok "transfer $transfer_id: $AMOUNT INR $SENDER -> $RECIPIENT COMPLETED, sender debited exactly once"

    http -X POST "$API/api/v1/transfers" -H "Authorization: Bearer $sender_token" \
        -H "Idempotency-Key: $key" -H 'Content-Type: application/json' --data-binary "$body" \
        || die "core-app not reachable"
    [[ "$STATUS" == "201" && "$(json_field id "$BODY")" == "$transfer_id" ]] \
        || die "idempotent replay -> HTTP $STATUS: $BODY"
    grep -qi '^Idempotent-Replayed: true' <<<"$HEADERS" || die "replay without Idempotent-Replayed: true"
    [[ "$(available_of "$sender_token")" == "$after" ]] || die "the replay moved money again"
    ok "same Idempotency-Key replayed, no second debit"

    http "$API/api/v1/transfers/$transfer_id" -H "Authorization: Bearer $recipient_token" \
        || die "core-app not reachable"
    [[ "$STATUS" == "200" ]] || die "GET /api/v1/transfers/{id} as $RECIPIENT -> HTTP $STATUS"
    ok "$RECIPIENT reads the transfer"

    echo "smoke: PASSED ($API)"
}

main "$@"
