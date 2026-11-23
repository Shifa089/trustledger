#!/usr/bin/env bash
# Load-test users for M13 (load-tests/README.md). LOCAL TESTING ONLY, fake users only.
#
#   load-tests/keycloak-perf-users.sh [COUNT]      (default 200, at most 999)
#
# Creates in the local "trustledger" realm, through the admin REST API's partialImport (existing ones are SKIPPED,
# so running it again is safe):
#   - client "trustledger-perf": public, password grant only, access tokens valid 30 minutes (the realm default is
#     5), so the tokens k6 fetches once in setup() outlive a whole run. Never create it outside a developer machine.
#   - users perf001..perfNNN with role CUSTOMER and password TRUSTLEDGER_PERF_USER_PASSWORD. Each user's Keycloak ID
#     is fixed (PerfSeedIds.subject(n) in core-app: 0e7f0001-0000-4000-8000-<n, 12 digits>), so the "sub" of their
#     tokens equals the keycloak_subject the "perf" profile seeds, and their wallet is the seeded FULL wallet.
#
# Needs Git Bash with curl. Run from dev/. Reads .env (KC_BOOTSTRAP_ADMIN_*, TRUSTLEDGER_PERF_USER_PASSWORD).
# Never prints a secret or a token.

set -euo pipefail

KEYCLOAK="${TRUSTLEDGER_KEYCLOAK:-http://localhost:8180}"
ENV_FILE="${TRUSTLEDGER_ENV_FILE:-.env}"
REALM=trustledger
SUBJECT_KIND=0e7f0001
COUNT="${1:-200}"

die() { echo "keycloak-perf-users: $*" >&2; exit 1; }

[[ "$COUNT" =~ ^[0-9]+$ ]] && (( COUNT >= 2 && COUNT <= 999 )) || die "COUNT must be 2..999 (got $COUNT)"
[[ "$KEYCLOAK" =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]+)?$ ]] \
    || die "refusing: TRUSTLEDGER_KEYCLOAK must be http://localhost or http://127.0.0.1 (got $KEYCLOAK)"
[[ -f "$ENV_FILE" ]] || die "no $ENV_FILE here; run from dev/ (copy .env.example to .env first)"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
[[ -n "${KC_BOOTSTRAP_ADMIN_USERNAME:-}" && -n "${KC_BOOTSTRAP_ADMIN_PASSWORD:-}" ]] \
    || die "KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD are not set in $ENV_FILE"
[[ -n "${TRUSTLEDGER_PERF_USER_PASSWORD:-}" && "$TRUSTLEDGER_PERF_USER_PASSWORD" != "change-me" ]] \
    || die "TRUSTLEDGER_PERF_USER_PASSWORD is not set in $ENV_FILE"

json_escape() { local s=${1//\/\\}; printf '%s' "${s//\"/\\\"}"; }
subject() { printf '%s-0000-4000-8000-%012d' "$SUBJECT_KIND" "$1"; }

admin_token=$(curl -sS --fail-with-body "$KEYCLOAK/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$KC_BOOTSTRAP_ADMIN_USERNAME" \
    --data-urlencode "password=$KC_BOOTSTRAP_ADMIN_PASSWORD" \
    | sed -nE 's/.*"access_token":"([^"]*)".*/\1/p') || die "admin login failed"
[[ -n "$admin_token" ]] || die "admin login returned no token"

password=$(json_escape "$TRUSTLEDGER_PERF_USER_PASSWORD")
body=$(mktemp)
trap 'rm -f "$body"' EXIT
{
    printf '{"ifResourceExists":"SKIP","clients":[{'
    printf '"clientId":"trustledger-perf","name":"TrustLedger perf (LOCAL TESTING ONLY)",'
    printf '"description":"LOCAL TESTING ONLY. Password grant for the k6 load test (load-tests/). 30-minute access tokens.",'
    printf '"enabled":true,"protocol":"openid-connect","publicClient":true,"standardFlowEnabled":false,'
    printf '"implicitFlowEnabled":false,"directAccessGrantsEnabled":true,"serviceAccountsEnabled":false,'
    printf '"attributes":{"access.token.lifespan":"1800"},'
    printf '"protocolMappers":[{"name":"trustledger-api audience","protocol":"openid-connect",'
    printf '"protocolMapper":"oidc-audience-mapper","consentRequired":false,"config":{'
    printf '"included.custom.audience":"trustledger-api","access.token.claim":"true","id.token.claim":"false",'
    printf '"introspection.token.claim":"true"}}]}],"users":['
    for ((n = 1; n <= COUNT; n++)); do
        (( n > 1 )) && printf ','
        printf '{"id":"%s","username":"perf%03d","enabled":true,"emailVerified":true,' "$(subject "$n")" "$n"
        printf '"firstName":"Perf","lastName":"User %03d","email":"perf%03d@example.test",' "$n" "$n"
        printf '"credentials":[{"type":"password","value":"%s","temporary":false}],' "$password"
        printf '"realmRoles":["CUSTOMER"]}'
    done
    printf ']}'
} > "$body"

result=$(curl -sS --fail-with-body -X POST "$KEYCLOAK/admin/realms/$REALM/partialImport" \
    -H "Authorization: Bearer $admin_token" -H "Content-Type: application/json" --data-binary "@$body") \
    || die "partialImport failed: $result"
added=$(sed -nE 's/.*"added":([0-9]+).*/\1/p' <<<"$result")
skipped=$(sed -nE 's/.*"skipped":([0-9]+).*/\1/p' <<<"$result")
echo "keycloak-perf-users: added ${added:-?}, skipped ${skipped:-?} (client + $COUNT users)"

# The load test only works if Keycloak kept our IDs: check the first and the last user.
for n in 1 "$COUNT"; do
    found=$(curl -sS --fail-with-body \
        "$KEYCLOAK/admin/realms/$REALM/users?exact=true&username=$(printf 'perf%03d' "$n")" \
        -H "Authorization: Bearer $admin_token" | sed -nE 's/^\[\{"id":"([^"]*)".*/\1/p')
    [[ "$found" == "$(subject "$n")" ]] \
        || die "perf$(printf '%03d' "$n") has ID '${found}', expected $(subject "$n"). Delete the perf users and run again."
done
echo "keycloak-perf-users: user IDs match the seeded subjects"
