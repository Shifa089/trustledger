// Manual test page for M7 (local profile only). Creates a top-up through the API, then opens Razorpay Checkout
// with the returned order ID and key ID. The wallet is credited by the payment.captured webhook (M7 part 3), so
// the status stays PENDING until that arrives.
(function () {
  'use strict';

  const API = '/api/v1/topups';
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

  async function pay() {
    if (!token()) {
      log('Missing token', 'Paste an access token first.');
      return;
    }
    const key = crypto.randomUUID();
    const amount = document.getElementById('amount').value.trim();
    const created = await call('POST', API, { amount: amount }, key);
    log('POST ' + API + ' (Idempotency-Key ' + key + ') -> ' + created.status, created.json);
    if (created.status !== 201) {
      return;
    }
    const topup = created.json;
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

  document.getElementById('pay').addEventListener('click', function () {
    pay().catch(function (e) { log('Error', String(e)); });
  });
  refreshButton.addEventListener('click', function () {
    refresh().catch(function (e) { log('Error', String(e)); });
  });
})();
