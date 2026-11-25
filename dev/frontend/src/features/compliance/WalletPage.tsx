import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useComplianceWallet, useWalletDecision, type ComplianceWallet } from '@/api/compliance'
import { ErrorAlert } from '@/components/ErrorAlert'
import { FormField } from '@/components/FormField'
import { Id, LoadingRows, PageHeader, ReasonForm, StatusBadge } from '@/components/staff'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatDateTime } from '@/lib/dates'
import { formatRupees } from '@/lib/money'
import { UUID_PATTERN } from '../transfer/schemas'

/** Wallet.MAX_REASON_LENGTH. */
export const MAX_FREEZE_REASON_LENGTH = 500

/**
 * /compliance/wallets and /compliance/wallets/:id (FR-W-3, FR-AML-3). Shows the wallet's real status from
 * GET /compliance/wallets/{id}, including a freeze the AML service asked for, then freeze or unfreeze with a reason.
 * A frozen wallet can't send or receive. The reason is for the record: the customer is never told it (no tipping off).
 */
export function WalletPage() {
  const { id } = useParams()
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Wallets" intro="Look up a wallet, then freeze or unfreeze it with a reason." />
      <WalletLookup key={`lookup-${id ?? ''}`} initial={id ?? ''} />
      {id !== undefined && <WalletDetail key={`detail-${id}`} walletId={id} />}
    </div>
  )
}

function WalletLookup({ initial }: { initial: string }) {
  const navigate = useNavigate()
  const [value, setValue] = useState(initial)
  const [error, setError] = useState<string | undefined>()
  return (
    <form
      aria-label="Find a wallet"
      noValidate
      className="flex flex-wrap items-end gap-3"
      onSubmit={(event) => {
        event.preventDefault()
        const id = value.trim()
        if (!UUID_PATTERN.test(id)) {
          setError('Enter a wallet ID (a UUID).')
          return
        }
        setError(undefined)
        void navigate(`/compliance/wallets/${id.toLowerCase()}`)
      }}
    >
      <div className="min-w-80 flex-1">
        <FormField id="wallet-id" label="Wallet ID" error={error}>
          <Input
            value={value}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => {
              setValue(event.target.value)
            }}
          />
        </FormField>
      </div>
      <Button type="submit">Find wallet</Button>
    </form>
  )
}

function WalletDetail({ walletId }: { walletId: string }) {
  const wallet = useComplianceWallet(walletId)
  const decision = useWalletDecision(walletId)
  const [done, setDone] = useState<string | undefined>()

  if (wallet.isPending) return <LoadingRows label="Loading the wallet" />
  if (wallet.isError) return <ErrorAlert error={wallet.error} title="Couldn't load the wallet" />
  const w = wallet.data
  const action = w.status === 'FROZEN' ? 'unfreeze' : w.status === 'ACTIVE' ? 'freeze' : undefined

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <WalletCard wallet={w} />
      <Card>
        <CardHeader>
          <CardTitle>{action === 'unfreeze' ? 'Unfreeze' : 'Freeze'}</CardTitle>
          <CardDescription>
            {action === 'unfreeze'
              ? 'Lets the wallet send and receive again. Lifting an AML freeze is always an officer decision.'
              : 'Stops the wallet sending and receiving. The customer is told it is frozen, never why.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <p aria-live="polite" className="text-sm font-medium text-emerald-700 dark:text-emerald-400">
            {done}
          </p>
          {decision.isError && <ErrorAlert error={decision.error} title="The change was not saved" />}
          {action === undefined ? (
            <p className="text-sm text-muted-foreground">A closed wallet can't be frozen or unfrozen.</p>
          ) : (
            <ReasonForm
              key={action}
              id={`${action}-reason`}
              label={action === 'freeze' ? 'Reason for freezing' : 'Reason for unfreezing'}
              hint="Kept in the wallet's history and the audit log."
              maxLength={MAX_FREEZE_REASON_LENGTH}
              submitLabel={action === 'freeze' ? 'Freeze wallet' : 'Unfreeze wallet'}
              pendingLabel={action === 'freeze' ? 'Freezing…' : 'Unfreezing…'}
              destructive={action === 'freeze'}
              pending={decision.isPending}
              onSubmit={async (reason) => {
                setDone(undefined)
                await decision.mutateAsync({ action, reason })
                setDone(action === 'freeze' ? 'Wallet frozen.' : 'Wallet unfrozen.')
              }}
            />
          )}
        </CardContent>
      </Card>
    </div>
  )
}

export function WalletCard({ wallet: w }: { wallet: ComplianceWallet }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-3">
          Wallet{' '}
          <span data-testid="wallet-status">
            <StatusBadge value={w.status} />
          </span>
        </CardTitle>
        <CardDescription className="font-mono text-xs">{w.walletId}</CardDescription>
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
          <dt className="text-muted-foreground">Customer</dt>
          <dd>
            <Id value={w.userId} />
          </dd>
          <dt className="text-muted-foreground">Tier</dt>
          <dd>{w.tier}</dd>
          <dt className="text-muted-foreground">Balance</dt>
          <dd className="tabular-nums">{formatRupees(w.balance)}</dd>
          <dt className="text-muted-foreground">Held</dt>
          <dd className="tabular-nums">{formatRupees(w.held)}</dd>
          <dt className="text-muted-foreground">Available</dt>
          <dd className="tabular-nums">{formatRupees(w.available)}</dd>
          {w.currentFreeze !== null && (
            <>
              <dt className="text-muted-foreground">Frozen by</dt>
              <dd data-testid="freeze-source">
                {w.currentFreeze.source === 'AML' ? 'AML service (automatic)' : 'Compliance officer'}
                {w.currentFreeze.frozenBy !== null && (
                  <>
                    {' '}
                    <Id value={w.currentFreeze.frozenBy} />
                  </>
                )}
              </dd>
              <dt className="text-muted-foreground">Frozen at</dt>
              <dd>{formatDateTime(w.currentFreeze.frozenAt)}</dd>
              <dt className="text-muted-foreground">Reason</dt>
              <dd>{w.currentFreeze.reason}</dd>
            </>
          )}
        </dl>
      </CardContent>
    </Card>
  )
}
