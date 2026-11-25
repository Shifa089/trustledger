import { zodResolver } from '@hookform/resolvers/zod'
import { CircleAlert, CircleCheck, Clock } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link } from 'react-router'
import { isApiError } from '@/api/errors'
import { newIdempotencyKey, useCreateTransfer } from '@/api/payments'
import type { MyWallet, Transfer, TransferRequest } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input, Select, Textarea } from '@/components/ui/input'
import { formatRupees } from '@/lib/money'
import { shortId } from '../history/transactions'
import {
  MAX_NOTE_LENGTH,
  toTransferRequest,
  transferSchema,
  type TransferData,
  type TransferValues,
} from './schemas'

const EMPTY: TransferValues = { recipientType: 'mobile', recipient: '', amount: '', note: '' }

/** Failures where the request may or may not have reached the server: retrying with the same key is safe. */
const UNCERTAIN = new Set(['NETWORK_ERROR', 'INTERNAL_ERROR', 'REQUEST_IN_PROGRESS', 'GATEWAY_UNAVAILABLE'])

/** A confirmed attempt: the exact body and the one Idempotency-Key that goes with it. */
interface Attempt {
  values: TransferValues
  body: TransferRequest
  idempotencyKey: string
}

type Step = { kind: 'edit' } | { kind: 'confirm'; attempt: Attempt } | { kind: 'done'; transfer: Transfer }

/**
 * Send money (FR-P-1..3): details → confirm → result.
 *
 * Double payments are prevented in two layers. In the browser, pressing "Review" fixes the body and makes one
 * Idempotency-Key for that attempt; "Send" (and "Try again" after a failure) always sends that same key, and a
 * synchronous in-flight flag drops a second click before React has even re-rendered the disabled button. On the
 * server, a key that already succeeded replays the stored answer instead of moving money again. Going back to edit
 * throws the key away, so changed details are always a new attempt.
 */
export function SendMoneyForm({ wallet }: { wallet: MyWallet }) {
  const [step, setStep] = useState<Step>({ kind: 'edit' })
  const [draft, setDraft] = useState<TransferValues>(EMPTY)

  if (step.kind === 'done') {
    return (
      <TransferResult
        transfer={step.transfer}
        onAgain={() => {
          setDraft(EMPTY)
          setStep({ kind: 'edit' })
        }}
      />
    )
  }
  if (step.kind === 'confirm') {
    return (
      <ConfirmTransfer
        attempt={step.attempt}
        onBack={() => {
          setDraft(step.attempt.values)
          setStep({ kind: 'edit' })
        }}
        onDone={(transfer) => {
          setStep({ kind: 'done', transfer })
        }}
      />
    )
  }
  return (
    <TransferDetails
      wallet={wallet}
      defaultValues={draft}
      onReview={(values, data) => {
        setStep({
          kind: 'confirm',
          attempt: { values, body: toTransferRequest(data), idempotencyKey: newIdempotencyKey() },
        })
      }}
    />
  )
}

function TransferDetails({
  wallet,
  defaultValues,
  onReview,
}: {
  wallet: MyWallet
  defaultValues: TransferValues
  onReview: (values: TransferValues, data: TransferData) => void
}) {
  const schema = useMemo(() => transferSchema(wallet.walletId), [wallet.walletId])
  const {
    register,
    handleSubmit,
    getValues,
    control,
    formState: { errors },
  } = useForm<TransferValues, unknown, TransferData>({
    resolver: zodResolver(schema),
    defaultValues,
    mode: 'onTouched',
  })
  const byMobile = useWatch({ control, name: 'recipientType' }) === 'mobile'

  const onSubmit = handleSubmit((data) => {
    onReview(getValues(), data)
  })

  return (
    <form noValidate onSubmit={(event) => void onSubmit(event)} className="flex max-w-lg flex-col gap-5" aria-label="Send money">
      <FormField id="recipientType" label="Send to">
        <Select {...register('recipientType')}>
          <option value="mobile">Mobile number</option>
          <option value="walletId">Wallet ID</option>
        </Select>
      </FormField>
      <FormField
        id="recipient"
        label={byMobile ? 'Recipient mobile number' : 'Recipient wallet ID'}
        hint={byMobile ? '10 digits, without +91.' : 'Ask the recipient for the wallet ID shown on their Send money page.'}
        error={errors.recipient?.message}
      >
        {byMobile ? (
          <Input type="tel" inputMode="numeric" maxLength={10} autoComplete="off" {...register('recipient')} />
        ) : (
          <Input autoComplete="off" spellCheck={false} className="font-mono" {...register('recipient')} />
        )}
      </FormField>
      <FormField
        id="amount"
        label="Amount (₹)"
        hint={`Available: ${formatRupees(wallet.available)}`}
        error={errors.amount?.message}
      >
        <Input inputMode="decimal" autoComplete="off" placeholder="0.00" {...register('amount')} />
      </FormField>
      <FormField id="note" label="Note (optional)" hint={`At most ${MAX_NOTE_LENGTH} characters.`} error={errors.note?.message}>
        <Textarea maxLength={MAX_NOTE_LENGTH} {...register('note')} />
      </FormField>
      <div>
        <Button type="submit">Review</Button>
      </div>
    </form>
  )
}

function ConfirmTransfer({
  attempt,
  onBack,
  onDone,
}: {
  attempt: Attempt
  onBack: () => void
  onDone: (transfer: Transfer) => void
}) {
  const transfer = useCreateTransfer()
  // A ref, not state: two clicks in the same tick both see the state from before the first one re-rendered.
  const inFlight = useRef(false)
  const { body } = attempt
  const recipient = body.recipientMobile ?? body.recipientWalletId ?? ''

  const send = async () => {
    if (inFlight.current) {
      return
    }
    inFlight.current = true
    try {
      onDone(await transfer.mutateAsync({ body, idempotencyKey: attempt.idempotencyKey }))
    } catch {
      // Shown from transfer.error below.
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Card className="max-w-lg">
      <CardHeader>
        <CardTitle>Confirm the transfer</CardTitle>
        <CardDescription>Check the details. Money sent to a wallet can't be taken back.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
          <dt className="text-muted-foreground">To</dt>
          <dd className="font-mono break-all" data-testid="confirm-recipient">
            {body.recipientMobile === undefined ? 'Wallet ' : 'Mobile '}
            {recipient}
          </dd>
          <dt className="text-muted-foreground">Amount</dt>
          <dd className="text-lg font-semibold" data-testid="confirm-amount">
            {formatRupees(body.amount)}
          </dd>
          {body.note !== undefined && (
            <>
              <dt className="text-muted-foreground">Note</dt>
              <dd className="break-words">{body.note}</dd>
            </>
          )}
        </dl>
        {transfer.isError && <TransferError error={transfer.error} />}
        <div className="flex flex-wrap gap-3">
          <Button type="button" disabled={transfer.isPending} onClick={() => void send()}>
            {transfer.isPending ? 'Sending…' : transfer.isError ? 'Try again' : `Send ${formatRupees(body.amount)}`}
          </Button>
          <Button type="button" variant="outline" disabled={transfer.isPending} onClick={onBack}>
            Back to edit
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

function TransferError({ error }: { error: unknown }) {
  const code = isApiError(error) ? error.errorCode : 'UNKNOWN_ERROR'
  const message =
    code === 'NOT_FOUND'
      ? 'No wallet was found for this recipient. Check the mobile number or wallet ID.'
      : isApiError(error)
        ? error.message
        : 'Something went wrong.'
  const fieldHints = isApiError(error) ? error.violations : []
  return (
    <Alert variant="destructive" data-error-code={code}>
      <CircleAlert aria-hidden />
      <AlertTitle>{UNCERTAIN.has(code) ? 'Not confirmed yet' : 'Transfer not sent'}</AlertTitle>
      <AlertDescription>
        <p>{message}</p>
        {fieldHints.map((v) => (
          <p key={`${v.field}:${v.message}`}>{v.message}</p>
        ))}
        {UNCERTAIN.has(code) && (
          <p>Trying again is safe: it sends the same request, so the money can only move once.</p>
        )}
        {code === 'KYC_REQUIRED' && (
          <Link to="/customer/kyc" className="underline">
            Go to KYC
          </Link>
        )}
        {isApiError(error) && error.correlationId !== undefined && (
          <p className="font-mono text-xs">Reference: {error.correlationId}</p>
        )}
      </AlertDescription>
    </Alert>
  )
}

function TransferResult({ transfer, onAgain }: { transfer: Transfer; onAgain: () => void }) {
  const held = transfer.status === 'HELD_FOR_REVIEW'
  return (
    <Card className="max-w-lg" data-testid="transfer-result" data-status={transfer.status}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          {held ? <Clock aria-hidden className="size-5" /> : <CircleCheck aria-hidden className="size-5" />}
          {held ? 'On hold for review' : 'Money sent'}
        </CardTitle>
        <CardDescription>
          {held
            ? `${formatRupees(transfer.amount)} is on hold while our compliance team reviews this transfer. The money has left your available balance but has not been lost: it shows as "held" on your dashboard. If the transfer is rejected, it comes back to you.`
            : `${formatRupees(transfer.amount)} was sent to wallet ${shortId(transfer.receiverWalletId)}.`}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-xs text-muted-foreground">
          Transfer reference: <span className="font-mono">{transfer.id}</span>
        </p>
        <div className="flex flex-wrap gap-3">
          <Button type="button" onClick={onAgain}>
            Send more money
          </Button>
          <Button asChild variant="outline">
            <Link to="/customer/history">View history</Link>
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
