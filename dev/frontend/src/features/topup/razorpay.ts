/**
 * Razorpay Checkout (test mode) for adding money (FR-P-4).
 *
 * Razorpay's own script is loaded once, on first use, from checkout.razorpay.com (it is not an npm package: Razorpay
 * only ships it as a hosted script). The order ID and the public test key ID come from POST /topups; no secret is
 * ever in the browser. What Checkout reports back is informational only: the wallet is credited by the signed
 * `payment.captured` webhook (or the reconciler), never because the browser said "paid".
 *
 * A deployment with a Content-Security-Policy must allow script-src https://checkout.razorpay.com and frame-src
 * https://api.razorpay.com (ADR 0023).
 */

export const RAZORPAY_SCRIPT_URL = 'https://checkout.razorpay.com/v1/checkout.js'

export interface CheckoutRequest {
  keyId: string
  orderId: string
  currency: string
  description: string
  /** The customer paid; the payment ID is Razorpay's (pay_...). */
  onPaid: (paymentId: string) => void
  /** The customer closed Checkout without a successful payment. */
  onDismiss: () => void
  /** An attempt failed; Checkout stays open so the customer can try again. */
  onAttemptFailed: (reason: string) => void
}

/** Opens Checkout; rejects if the script can't load. Tests pass a fake. */
export type OpenCheckout = (request: CheckoutRequest) => Promise<void>

interface RazorpayInstance {
  open: () => void
  on: (event: 'payment.failed', handler: (response: { error?: { description?: string } }) => void) => void
}

type RazorpayConstructor = new (options: Record<string, unknown>) => RazorpayInstance

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor
  }
}

let scriptLoad: Promise<RazorpayConstructor> | undefined

function loadScript(): Promise<RazorpayConstructor> {
  if (window.Razorpay !== undefined) {
    return Promise.resolve(window.Razorpay)
  }
  scriptLoad ??= new Promise<RazorpayConstructor>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = RAZORPAY_SCRIPT_URL
    script.async = true
    script.onload = () => {
      if (window.Razorpay === undefined) {
        reject(new Error('Razorpay Checkout did not load.'))
      } else {
        resolve(window.Razorpay)
      }
    }
    script.onerror = () => {
      scriptLoad = undefined // allow a later retry
      script.remove()
      reject(new Error('Razorpay Checkout could not be loaded.'))
    }
    document.head.appendChild(script)
  })
  return scriptLoad
}

export const openRazorpayCheckout: OpenCheckout = async (request) => {
  const Razorpay = await loadScript()
  const checkout = new Razorpay({
    key: request.keyId,
    order_id: request.orderId,
    currency: request.currency,
    name: 'TrustLedger (test mode)',
    description: request.description,
    handler: (response: { razorpay_payment_id?: string }) => {
      request.onPaid(response.razorpay_payment_id ?? '')
    },
    modal: { ondismiss: request.onDismiss },
  })
  checkout.on('payment.failed', (response) => {
    request.onAttemptFailed(response.error?.description ?? 'The payment failed.')
  })
  checkout.open()
}
