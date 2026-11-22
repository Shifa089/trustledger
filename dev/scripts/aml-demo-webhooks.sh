#!/usr/bin/env bash
# AML demo helper (docs/aml-demo.md). LOCAL TESTING ONLY, Razorpay TEST MODE only, fake users only.
#
#   scripts/aml-demo-webhooks.sh capture TOPUP_ID [TOPUP_ID...]
#       For each PENDING top-up of customer1: reads its Razorpay order and amount from the API, builds a
#       payment.captured webhook for it (made-up pay_Demo... payment ID), signs the exact body bytes with
#       RAZORPAY_WEBHOOK_SECRET (hex HMAC-SHA256, like Razorpay) and POSTs it to /api/v1/webhooks/razorpay.
#       It stands in for "pay on checkout.html and wait for Razorpay's webhook" (docs/e2e-razorpay.md).
#
#   scripts/aml-demo-webhooks.sh totp
#       Prints the current 6-digit TOTP code of the staff test users (officer1, officer2, admin1 share
#       TRUSTLEDGER_DEV_TOTP_SECRET; HmacSHA1, 30 s, as in the realm export). Replaces oathtool.
#
# Needs Git Bash with curl, openssl, xxd. Run from dev/. Reads .env; never prints a secret or a token.
# API base URL: TRUSTLEDGER_API (default http://localhost:8080); anything but localhost/127.0.0.1 is refused,
# so a forged "payment" can never be sent to a shared or deployed environment.

set -euo pipefail

API="${TRUSTLEDGER_API:-http://localhost:8080}"
KEYCLOAK="${TRUSTLEDGER_KEYCLOAK:-http://localhost:8180}"
ENV_FILE="${TRUSTLEDGER_ENV_FILE:-.env}"

die() { echo "aml-demo: $*" >&2; exit 1; }

load_env() {
    [[ -f "$ENV_FILE" ]] || die "no $ENV_FILE here; run from dev/ (copy .env.example to .env first)"
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
}

require_local() {
    [[ "$API" =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]+)?$ ]] \
        || die "refusing: TRUSTLEDGER_API must be http://localhost or http://127.0.0.1 (got $API)"
    [[ "${RAZORPAY_KEY_ID:-}" == rzp_test_* ]] || die "refusing: RAZORPAY_KEY_ID is not a rzp_test_ key"
    [[ -n "${RAZORPAY_WEBHOOK_SECRET:-}" && "$RAZORPAY_WEBHOOK_SECRET" != "change-me" ]] \
        || die "RAZORPAY_WEBHOOK_SECRET is not set in $ENV_FILE"
}

# First string value of a top-level-ish JSON field; enough for our own flat responses (no jq on Git Bash).
json_field() {
    sed -nE "s/.*\"$1\":\"([^\"]*)\".*/\1/p" <<<"$2" | head -n 1
}

customer1_token() {
    local body
    body=$(curl -sS "$KEYCLOAK/realms/trustledger/protocol/openid-connect/token" \
        -d grant_type=password -d client_id=trustledger-dev -d username=customer1 \
        --data-urlencode "password=${TRUSTLEDGER_DEV_USER_PASSWORD:?TRUSTLEDGER_DEV_USER_PASSWORD missing}")
    local token
    token=$(json_field access_token "$body")
    [[ -n "$token" ]] || die "could not get a token for customer1 (Keycloak running? password right?)"
    printf '%s' "$token"
}

# "46000.00" -> 4600000 (paise). Integer arithmetic only, never floating point.
to_paise() {
    [[ "$1" =~ ^([0-9]+)\.([0-9]{2})$ ]] || die "unexpected amount format: $1"
    echo $(( 10#${BASH_REMATCH[1]} * 100 + 10#${BASH_REMATCH[2]} ))
}

random_alnum() {
    openssl rand -hex 16 | head -c "$1"
}

capture() {
    (( $# > 0 )) || die "usage: $0 capture TOPUP_ID [TOPUP_ID...]"
    load_env
    require_local
    local token
    token=$(customer1_token)

    local topup_id
    for topup_id in "$@"; do
        [[ "$topup_id" =~ ^[0-9a-fA-F-]{36}$ ]] || die "not a top-up ID: $topup_id"
        local topup status order_id amount currency
        topup=$(curl -sS -H "Authorization: Bearer $token" "$API/api/v1/topups/$topup_id")
        status=$(json_field status "$topup")
        order_id=$(json_field gatewayOrderId "$topup")
        amount=$(json_field amount "$topup")
        currency=$(json_field currency "$topup")
        [[ -n "$order_id" && -n "$amount" ]] || die "top-up $topup_id not readable as customer1: $topup"
        if [[ "$status" != "PENDING" ]]; then
            echo "top-up $topup_id is $status, not PENDING: skipped"
            continue
        fi

        local paise payment_id event_id now body signature http_code
        paise=$(to_paise "$amount")
        payment_id="pay_Demo$(random_alnum 14)"
        event_id="evt_demo_$(random_alnum 16)"
        now=$(date +%s)
        # Shape of a Razorpay payment.captured webhook, minus fields core-app ignores. No personal data.
        body=$(printf '{"entity":"event","account_id":"acc_Demo","event":"payment.captured","contains":["payment"],"payload":{"payment":{"entity":{"id":"%s","entity":"payment","amount":%d,"currency":"%s","status":"captured","order_id":"%s","method":"upi","captured":true,"created_at":%d}}},"created_at":%d}' \
            "$payment_id" "$paise" "${currency:-INR}" "$order_id" "$now" "$now")
        # Sign exactly the bytes we send (printf, no trailing newline; --data-binary sends them unchanged).
        signature=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$RAZORPAY_WEBHOOK_SECRET" -r | cut -d' ' -f1)

        http_code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/webhooks/razorpay" \
            -H 'Content-Type: application/json' \
            -H "X-Razorpay-Signature: $signature" \
            -H "X-Razorpay-Event-Id: $event_id" \
            --data-binary "$body")
        echo "top-up $topup_id: payment.captured $payment_id ($amount $currency), event $event_id -> HTTP $http_code"
        [[ "$http_code" == "200" ]] || die "webhook refused (HTTP $http_code); see the core-app log"
    done
}

# RFC 6238 TOTP: HMAC-SHA1(secret, 8-byte big-endian counter), dynamic truncation, 6 digits.
totp() {
    load_env
    local secret="${TRUSTLEDGER_DEV_TOTP_SECRET:?TRUSTLEDGER_DEV_TOTP_SECRET missing}"
    local counter hex offset code
    counter=$(( $(date +%s) / 30 ))
    hex=$(printf '%016x' "$counter" | xxd -r -p | openssl dgst -sha1 -hmac "$secret" -binary | xxd -p | tr -d '\n')
    offset=$(( 16#${hex:39:1} * 2 ))
    code=$(( (16#${hex:offset:8} & 0x7fffffff) % 1000000 ))
    printf '%06d\n' "$code"
    echo "valid for $(( 30 - $(date +%s) % 30 )) more seconds; one login per user per code" >&2
}

case "${1:-}" in
    capture) shift; capture "$@" ;;
    totp) totp ;;
    *) die "usage: $0 capture TOPUP_ID [TOPUP_ID...] | $0 totp" ;;
esac
