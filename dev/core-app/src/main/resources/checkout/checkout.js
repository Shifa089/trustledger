// Manual test page for M7 (local profile only). Either creates a top-up through the API, or reads an existing
// PENDING one (for example created in http/topups.http), then opens Razorpay Checkout with the top-up's order ID
// and key ID. The wallet is credited by the signed payment.captured webhook, so the status stays PENDING until
// that arrives. Steps: docs/e2e-razorpay.md.
(function () {
  'use strict';

  const API = '/api/v1/topups';
  // Checked before the ID goes into a URL path, so the page can only ever call GET /api/v1/topups/{uuid}.
  const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const logEl = document.getElementById('log');
  const refreshButton = document.getElementById('refresh');
  let currentTopupId = null;

  function log(label, value) {
    const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    logEl.textContent += '[' + new Date().toLocaleTimeString() + '] ' + label + '\n' + text + '\n\n';
  }

  function token() {
    return document.getElementById('token').value.trim();
  }

  function requireToken() {
    if (!token()) {
      log('Missing token', 'Paste an access token first.');
      return false;
    }
    return true;
  }

  async function call(method, url, body, idempotencyKey) {
    const headers = { 'Authorization': 'Bearer ' + token() };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey;
    }
    const response = await fetch(url, {
      method: method, headers: headers, body: body === undefined ? undefined : JSON.stringify(body)
    });
    const json = await response.json().catch(function () { return null; });
    return { status: response.status, json: json };
  }

  async function refresh() {
    if (!currentTopupId) {
      return;
    }
    const result = await call('GET', API + '/' + currentTopupId);
    log('GET ' + API + '/' + currentTopupId + ' -> ' + result.status, result.json);
  }

  function openCheckout(topup) {
    currentTopupId = topup.id;
    refreshButton.disabled = false;

    const checkout = new Razorpay({
      key: topup.keyId,
      order_id: topup.gatewayOrderId,
      currency: topup.currency,
      name: 'TrustLedger (test)',
      description: 'Wallet top-up ' + topup.id,
      handler: function (response) {
        // The signature here is informational only: the server trusts the signed webhook, not the browser.
        log('Checkout success', response);
        refresh();
      },
      modal: {
        ondismiss: function () { log('Checkout closed', 'The top-up stays PENDING until the reconciler expires it.'); }
      }
    });
    checkout.on('payment.failed', function (response) { log('Payment failed', response.error); });
    checkout.open();
  }

  async function pay() {
    if (!requireToken()) {
      return;
    }
    const key = crypto.randomUUID();
    const amount = document.getElementById('amount').value.trim();
    const created = await call('POST', API, { amount: amount }, key);
    log('POST ' + API + ' (Idempotency-Key ' + key + ') -> ' + created.status, created.json);
    if (created.status !== 201) {
      return;
    }
    openCheckout(created.json);
  }

  async function payExisting() {
    if (!requireToken()) {
      return;
    }
    const id = document.getElementById('existing').value.trim();
    if (!UUID_PATTERN.test(id)) {
      log('Invalid top-up ID', 'Paste the "id" (a UUID) from the POST /api/v1/topups response.');
      return;
    }
    const found = await call('GET', API + '/' + id);
    log('GET ' + API + '/' + id + ' -> ' + found.status, found.json);
    if (found.status !== 200) {
      return;
    }
    const topup = found.json;
    // Only PENDING has a Razorpay order still waiting for payment. CAPTURED / PARKED are settled; FAILED and
    // EXPIRED are deliberately not reopened here (create a new top-up instead).
    if (topup.status !== 'PENDING' || !topup.gatewayOrderId) {
      log('Not payable', 'Top-up is ' + topup.status + '; only a PENDING top-up with an order can be paid.');
      return;
    }
    openCheckout(topup);
  }

  function onClick(id, action) {
    document.getElementById(id).addEventListener('click', function () {
      action().catch(function (e) { log('Error', String(e)); });
    });
  }

  onClick('pay', pay);
  onClick('payExisting', payExisting);
  onClick('refresh', refresh);
})();
