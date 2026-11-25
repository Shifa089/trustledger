import { zodResolver } from '@hookform/resolvers/zod'
import { CircleAlert, CircleCheck, Clock } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { isApiError } from '@/api/errors'
import { fetchTopup, newIdempotencyKey, useCreateTopup, useRefreshWallet } from '@/api/payments'
import type { Topup, TopupStatus } from '@/api/types'
import { ErrorAlert } from '@/components/ErrorAlert'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatRupees } from '@/lib/money'
import { openRazorpayCheckout, type OpenCheckout } from './razorpay'
import { topupSchema, type TopupData, type TopupValues } from './schemas'

/** How often, and how many times, GET /topups/{id} is asked after Checkout reports a payment (about a minute). */
export const TOPUP_POLL_INTERVAL_MS = 2000
export const TOPUP_MAX_POLLS = 30

/** Statuses that end polling: credited, parked, failed or expired. CREATED and PENDING are still open. */
const FINAL: ReadonlySet<TopupStatus> = new Set(['CAPTURED', 'PARKED', 'FAILED', 'EXPIRED'])

type Phase =
  | { kind: 'edit' }
  | { kind: 'checkout'; topup: Topup }
  | { kind: 'polling'; topup: Topup }
  | { kind: 'final'; topup: Topup }
  | { kind: 'slow'; topup: Topup }
  | { kind: 'dismissed'; topup: Topup }
  | { kind: 'checkout-error'; topup: Topup; message: string }

export interface TopupFlowProps {
  openCheckout?: OpenCheckout
  pollIntervalMs?: number
  maxPolls?: number
  /** How much more the wallet can take (balance cap and monthly load), as a hint; the server enforces it. */
  roomLeft?: string
}

/**
 * Add money (FR-P-4): amount → POST /topups → Razorpay Checkout (test mode) → poll GET /topups/{id}.
 *
 * The Idempotency-Key is made once per form submission and reused when that submission is retried (same amount after
 * a failed create), so a timeout can never create two orders. A new amount, or a finished top-up, gets a new key.
 * The wallet is credited by the signed webhook, not by the browser: if that takes longer than the polling window,
 * the page says so and the reconciler (FR-P-6) finishes the job.
 */
export function TopupFlow({
  openCheckout = openRazorpayCheckout,
  pollIntervalMs = TOPUP_POLL_INTERVAL_MS,
  maxPolls = TOPUP_MAX_POLLS,
  roomLeft,
}: TopupFlowProps) {
  const create = useCreateTopup()
  const refreshWallet = useRefreshWallet()
  const [phase, setPhase] = useState<Phase>({ kind: 'edit' })
  const [pollRun, setPollRun] = useState(0)
  const [lastAttemptFailure, setLastAttemptFailure] = useState<string>()
  const inFlight = useRef(false)
  /** The submission a retry belongs to: same amount → same key. */
  const submission = useRef<{ amount: string; key: string } | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<TopupValues, unknown, TopupData>({
    resolver: zodResolver(topupSchema),
    defaultValues: { amount: '' },
    mode: 'onTouched',
  })

  const startCheckout = async (topup: Topup) => {
    if (topup.gatewayOrderId === null || topup.keyId === null) {
      setPhase({ kind: 'checkout-error', topup, message: 'The payment order is not ready. Please try again.' })
      return
    }
    setLastAttemptFailure(undefined)
    setPhase({ kind: 'checkout', topup })
    try {
      await openCheckout({
        keyId: topup.keyId,
        orderId: topup.gatewayOrderId,
        currency: topup.currency,
        description: `Add ${formatRupees(topup.amount)} to your wallet`,
        onPaid: () => {
          setPhase({ kind: 'polling', topup })
          setPollRun((run) => run + 1)
        },
        onDismiss: () => {
          setPhase({ kind: 'dismissed', topup })
        },
        onAttemptFailed: setLastAttemptFailure,
      })
    } catch {
      setPhase({
        kind: 'checkout-error',
        topup,
        message: "Razorpay Checkout couldn't be loaded. Check your connection (or an ad blocker) and try again.",
      })
    }
  }

  const submitAmount = async ({ amount }: TopupData) => {
    if (inFlight.current) {
      return
    }
    inFlight.current = true
    if (submission.current?.amount !== amount) {
      submission.current = { amount, key: newIdempotencyKey() }
    }
    try {
      const topup = await create.mutateAsync({ body: { amount }, idempotencyKey: submission.current.key })
      submission.current = null
      await startCheckout(topup)
    } catch {
      // Shown from create.error; the key is kept for a retry of this same amount.
    } finally {
      inFlight.current = false
    }
  }

  const pollingId = phase.kind === 'polling' ? phase.topup.id : undefined

  useEffect(() => {
    if (pollingId === undefined) {
      return undefined
    }
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    let attempts = 0
    const tick = async () => {
      attempts += 1
      try {
        const topup = await fetchTopup(pollingId, controller.signal)
        if (controller.signal.aborted) return
        if (FINAL.has(topup.status)) {
          setPhase({ kind: 'final', topup })
          if (topup.status === 'CAPTURED') {
            void refreshWallet()
          }
          return
        }
      } catch {
        if (controller.signal.aborted) return
        // A failed read is not a failed payment: keep asking until the window ends.
      }
      if (attempts >= maxPolls) {
        setPhase((current) => (current.kind === 'polling' ? { kind: 'slow', topup: current.topup } : current))
        return
      }
      timer = setTimeout(() => void tick(), pollIntervalMs)
    }
    timer = setTimeout(() => void tick(), pollIntervalMs)
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [pollingId, pollRun, pollIntervalMs, maxPolls, refreshWallet])

  const checkAgain = (topup: Topup) => {
    setPhase({ kind: 'polling', topup })
    setPollRun((run) => run + 1)
  }
  const startOver = () => {
    reset({ amount: '' })
    create.reset()
    setPhase({ kind: 'edit' })
  }

  if (phase.kind === 'edit') {
    return (
      <form noValidate onSubmit={(event) => void handleSubmit(submitAmount)(event)} className="flex max-w-lg flex-col gap-5" aria-label="Add money">
        <FormField
          id="topup-amount"
          label="Amount (₹)"
          hint={roomLeft === undefined ? undefined : `You can add up to ${formatRupees(roomLeft)} now.`}
          error={errors.amount?.message}
        >
          <Input inputMode="decimal" autoComplete="off" placeholder="0.00" {...register('amount')} />
        </FormField>
        {create.isError && <ErrorAlert error={create.error} title="Couldn't start the top-up" />}
        {create.isError && isApiError(create.error) && create.error.status === 0 && (
          <p className="text-sm text-muted-foreground">
            Trying again with the same amount is safe: it reuses the same request, so only one order is created.
          </p>
        )}
        <div>
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? 'Starting…' : 'Add money'}
          </Button>
        </div>
      </form>
    )
  }

  const { topup } = phase
  return (
    <Card className="max-w-lg" data-testid="topup-state" data-phase={phase.kind}>
      {phase.kind === 'checkout' && (
        <Status icon="wait" title="Complete the payment in Razorpay Checkout">
          Paying {formatRupees(topup.amount)} in test mode. Use Razorpay's test card or UPI details.
          {lastAttemptFailure !== undefined && <span className="block">Last attempt failed: {lastAttemptFailure}</span>}
        </Status>
      )}
      {phase.kind === 'polling' && (
        <Status icon="wait" title="Confirming your payment…">
          Razorpay accepted the payment. We're waiting for its confirmation before crediting{' '}
          {formatRupees(topup.amount)}.
        </Status>
      )}
      {phase.kind === 'final' && <FinalStatus topup={topup} />}
      {phase.kind === 'slow' && (
        <Status icon="wait" title="We'll confirm it shortly">
          Your payment is with Razorpay, but its confirmation hasn't reached us yet. You don't need to pay again: we
          check pending top-ups automatically, and {formatRupees(topup.amount)} will appear in your balance once it is
          confirmed.
        </Status>
      )}
      {phase.kind === 'dismissed' && (
        <Status icon="error" title="No payment was made">
          Checkout was closed before a payment went through. If you did pay, check again in a moment.
          {lastAttemptFailure !== undefined && <span className="block">Last attempt failed: {lastAttemptFailure}</span>}
        </Status>
      )}
      {phase.kind === 'checkout-error' && (
        <Status icon="error" title="Checkout didn't open">
          {phase.message}
        </Status>
      )}
      <CardContent className="flex flex-wrap gap-3">
        {(phase.kind === 'slow' || phase.kind === 'dismissed') && (
          <Button type="button" variant="outline" onClick={() => {
              checkAgain(topup)
            }}>
            Check again
          </Button>
        )}
        {phase.kind === 'checkout-error' && (
          <Button type="button" variant="outline" onClick={() => void startCheckout(topup)}>
            Open Checkout again
          </Button>
        )}
        {phase.kind !== 'checkout' && phase.kind !== 'polling' && (
          <Button type="button" onClick={startOver}>
            {phase.kind === 'final' && topup.status === 'CAPTURED' ? 'Add more money' : 'Start a new top-up'}
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

function FinalStatus({ topup }: { topup: Topup }) {
  switch (topup.status) {
    case 'CAPTURED':
      return (
        <Status icon="ok" title="Money added">
          {formatRupees(topup.amount)} was added to your wallet.
        </Status>
      )
    case 'PARKED':
      return (
        <Status icon="wait" title="Received, waiting for review">
          We received {formatRupees(topup.amount)}, but your wallet couldn't take it (a limit was reached, or the wallet
          is frozen). The money is safe and an operator will settle it.
        </Status>
      )
    default:
      return (
        <Status icon="error" title={topup.status === 'EXPIRED' ? 'Top-up expired' : 'Payment failed'}>
          No money was added. If money did leave your account, it will still be credited automatically once Razorpay
          confirms it.
        </Status>
      )
  }
}

function Status({ icon, title, children }: { icon: 'ok' | 'wait' | 'error'; title: string; children: ReactNode }) {
  const Icon = icon === 'ok' ? CircleCheck : icon === 'wait' ? Clock : CircleAlert
  return (
    <CardHeader>
      <CardTitle className="flex items-center gap-2" role="status">
        <Icon aria-hidden className="size-5" />
        {title}
      </CardTitle>
      <CardDescription>{children}</CardDescription>
    </CardHeader>
  )
}
