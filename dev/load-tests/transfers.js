// k6 load test of POST /api/v1/transfers (M13, SPEC §2 "Performance target"). LOCAL TESTING ONLY, fake users only.
// How to run it: load-tests/README.md.
//
//   k6 run -e PERF_PASSWORD=... load-tests/transfers.js                 # 10, 50, 100 req/s, 2 minutes each
//   k6 run -e PERF_PASSWORD=... -e MODE=steady load-tests/transfers.js  # 100 req/s for 2 minutes (VT on/off)
//
// Env: PERF_PASSWORD (required, = TRUSTLEDGER_PERF_USER_PASSWORD), API (default http://localhost:8080),
// KEYCLOAK (default http://localhost:8180), WALLETS (default 200), MODE (ramp | steady), RATE (steady only, 100).
//
// Tokens: setup() fetches one real Keycloak access token per perf user (client trustledger-perf, 30-minute tokens,
// created by keycloak-perf-users.sh) and hands all of them to every VU. The app validates each one like in
// production (RS256 signature, issuer, audience); no request waits for Keycloak during the test.

import http from 'k6/http';
import { check, fail } from 'k6';

const API = __ENV.API || 'http://localhost:8080';
const KEYCLOAK = __ENV.KEYCLOAK || 'http://localhost:8180';
const WALLETS = parseInt(__ENV.WALLETS || '200', 10);
const MODE = __ENV.MODE || 'ramp';
const STEADY_RATE = parseInt(__ENV.RATE || '100', 10);
const STAGE_DURATION = '2m';

// Must match PerfSeedIds in core-app (PerfSeedIdsTest checks these prefixes).
const SUBJECT_KIND = '0e7f0001';
const WALLET_KIND = '0e7f0003';

function pad(n, width) {
  return String(n).padStart(width, '0');
}

function walletId(n) {
  return `${WALLET_KIND}-0000-4000-8000-${pad(n, 12)}`;
}

function stage(rate, startTime) {
  return {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: STAGE_DURATION,
    startTime,
    preAllocatedVUs: Math.max(20, rate),
    maxVUs: Math.max(100, rate * 4),
    gracefulStop: '10s',
  };
}

const scenarios = MODE === 'steady'
  ? { [`rps${STEADY_RATE}`]: stage(STEADY_RATE, '0s') }
  : { rps10: stage(10, '0s'), rps50: stage(50, '2m10s'), rps100: stage(100, '4m20s') };

// Only the transfers count (tag name:transfer); the token requests of setup() don't.
const thresholds = {
  'http_req_duration{name:transfer}': ['p(99)<300'],
  'http_req_failed{name:transfer}': ['rate<0.01'],
  checks: ['rate>0.99'],
};
for (const name of Object.keys(scenarios)) {
  // Per stage, so the summary shows p95/p99 and the error rate of each load level.
  thresholds[`http_req_duration{name:transfer,scenario:${name}}`] = ['p(99)<300'];
  thresholds[`http_req_failed{name:transfer,scenario:${name}}`] = ['rate<0.01'];
  thresholds[`http_reqs{name:transfer,scenario:${name}}`] = ['count>0'];
}

export const options = {
  scenarios,
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '2m',
};

export function setup() {
  if (!__ENV.PERF_PASSWORD) {
    fail('Set PERF_PASSWORD (the TRUSTLEDGER_PERF_USER_PASSWORD from .env)');
  }
  const url = `${KEYCLOAK}/realms/trustledger/protocol/openid-connect/token`;
  const users = [];
  const batchSize = 20;
  for (let first = 1; first <= WALLETS; first += batchSize) {
    const batch = [];
    for (let n = first; n < first + batchSize && n <= WALLETS; n++) {
      batch.push(['POST', url, {
        grant_type: 'password',
        client_id: 'trustledger-perf',
        username: `perf${pad(n, 3)}`,
        password: __ENV.PERF_PASSWORD,
      }, { tags: { name: 'token' } }]);
    }
    const responses = http.batch(batch);
    responses.forEach((res, i) => {
      const n = first + i;
      if (res.status !== 200) {
        fail(`token for perf${pad(n, 3)}: HTTP ${res.status}. Did you run load-tests/keycloak-perf-users.sh?`);
      }
      users.push({ token: res.json('access_token'), wallet: walletId(n) });
    });
  }
  const lifespan = http.post(url, {
    grant_type: 'password', client_id: 'trustledger-perf', username: 'perf001', password: __ENV.PERF_PASSWORD,
  }, { tags: { name: 'token' } }).json('expires_in');
  if (lifespan < 900) {
    fail(`access tokens live ${lifespan}s; the run needs more. Check the trustledger-perf client.`);
  }
  // A subject check: the seeded wallet must be the token's own (the "perf" profile seeded it).
  const me = http.get(`${API}/api/v1/wallets/me`, {
    headers: { Authorization: `Bearer ${users[0].token}` }, tags: { name: 'setup-check' },
  });
  if (me.status !== 200 || me.json('walletId') !== users[0].wallet) {
    fail(`GET /wallets/me as perf001: HTTP ${me.status}. Is core-app running with profiles local,perf?`);
  }
  console.log(`setup: ${users.length} tokens, valid ${lifespan}s; subject prefix ${SUBJECT_KIND}`);
  return { users };
}

export default function (data) {
  const users = data.users;
  const from = Math.floor(Math.random() * users.length);
  let to = Math.floor(Math.random() * (users.length - 1));
  if (to >= from) {
    to += 1; // a random receiver that is never the sender
  }
  // ₹1.00 to ₹100.00: far below the ₹50,000 hold and the ₹1,00,000 daily limit for a whole day of runs.
  const paise = 100 + Math.floor(Math.random() * 9901);
  const amount = `${Math.floor(paise / 100)}.${pad(paise % 100, 2)}`;

  const res = http.post(`${API}/api/v1/transfers`,
    JSON.stringify({ recipientWalletId: users[to].wallet, amount }),
    {
      headers: {
        Authorization: `Bearer ${users[from].token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
      tags: { name: 'transfer' },
    });
  check(res, {
    'status is 201': (r) => r.status === 201,
    'transfer COMPLETED': (r) => r.status === 201 && r.json('status') === 'COMPLETED',
  });
}
