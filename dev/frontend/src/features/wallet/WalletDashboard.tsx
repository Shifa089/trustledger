import { Snowflake } from 'lucide-react'
import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { isNoWallet, useActivity, useLimitUsage, useMyWallet, useTransactions, WALLET_WAIT_POLLS } from '@/api/wallet'
import type { MyWallet } from '@/api/types'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { todayInIndia } from '@/lib/dates'
import { formatRupees } from '@/lib/money'
import { WhoAmICard } from '@/pages/WhoAmICard'
import { TransactionTable } from '../history/TransactionTable'
import { activityByDay } from './activity'
import { ActivityChart } from './ActivityChart'
import { LimitBars } from './LimitBars'

const RECENT_SIZE = 5

/**
 * /customer: the wallet at a glance (FR-W-1/2, FR-S-1). `today` is the Indian day the 30-day chart ends on (a prop
 * so tests can fix it).
 */
export function WalletDashboard({ today: todayProp }: { today?: string }) {
  const [today] = useState(() => todayProp ?? todayInIndia())
  const location = useLocation()
  const justOnboarded = (location.state as { justOnboarded?: boolean } | null)?.justOnboarded === true
  const wallet = useMyWallet({ waitForWallet: justOnboarded })

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Customer dashboard</h1>
        <p className="text-muted-foreground">Your TrustLedger wallet: balance, limits and recent activity.</p>
      </div>
      {wallet.isPending && <p className="text-sm text-muted-foreground">Loading your wallet…</p>}
      {wallet.isError &&
        (isNoWallet(wallet.error) ? (
          <NoWallet waiting={justOnboarded && wallet.errorUpdateCount < WALLET_WAIT_POLLS} />
        ) : (
          <ErrorAlert error={wallet.error} title="Couldn't load your wallet" />
        ))}
      {wallet.isSuccess && <WalletView wallet={wallet.data} today={today} />}
      <div className="grid gap-6 lg:grid-cols-2">
        <WhoAmICard />
      </div>
    </div>
  )
}

function NoWallet({ waiting }: { waiting: boolean }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{waiting ? 'Setting up your wallet…' : 'No wallet yet'}</CardTitle>
        <CardDescription>
          {waiting
            ? 'Your minimum KYC is done. The wallet appears here in a moment.'
            : 'Complete minimum KYC (name, date of birth, mobile and PAN) to get a wallet.'}
        </CardDescription>
      </CardHeader>
      {!waiting && (
        <CardContent>
          <Button asChild>
            <Link to="/customer/kyc">Complete KYC</Link>
          </Button>
        </CardContent>
      )}
    </Card>
  )
}

function WalletView({ wallet, today }: { wallet: MyWallet; today: string }) {
  const usage = useLimitUsage(true)
  const recent = useTransactions({ page: 0, size: RECENT_SIZE })
  const activity = useActivity(today, true)

  return (
    <>
      {wallet.status !== 'ACTIVE' && (
        <Alert variant="destructive">
          <Snowflake aria-hidden />
          <AlertTitle>Wallet {wallet.status === 'FROZEN' ? 'frozen' : 'closed'}</AlertTitle>
          <AlertDescription>
            <p>This wallet can't send or receive money right now. Contact support for help.</p>
          </AlertDescription>
        </Alert>
      )}
      <section aria-label="Balances" className="grid gap-4 sm:grid-cols-3">
        <Amount label="Balance" value={wallet.balance} />
        <Amount label="Held" value={wallet.held} hint="Reserved for transfers under review" />
        <Amount label="Available" value={wallet.available} hint="Balance minus held" />
      </section>
      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              Limits
              <Badge variant="secondary" data-testid="wallet-tier">
                {wallet.tier === 'FULL' ? 'Full KYC' : 'Minimum KYC'}
              </Badge>
              <Badge variant={wallet.status === 'ACTIVE' ? 'outline' : 'destructive'}>{wallet.status}</Badge>
            </CardTitle>
            <CardDescription>
              {wallet.tier === 'MIN' ? (
                <>
                  <Link to="/customer/kyc" className="underline underline-offset-4">
                    Complete full KYC
                  </Link>{' '}
                  for higher limits and to send money.
                </>
              ) : (
                'Days and months start at midnight India time.'
              )}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <LimitBars wallet={wallet} usage={usage.data} usageError={usage.isError} />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Activity, last 30 days</CardTitle>
          </CardHeader>
          <CardContent>
            {activity.isPending && <p className="text-sm text-muted-foreground">Loading activity…</p>}
            {activity.isError && <ErrorAlert error={activity.error} title="Couldn't load activity" />}
            {activity.isSuccess && (
              <ActivityChart summary={activityByDay(activity.data.items, today)} truncated={activity.data.truncated} />
            )}
          </CardContent>
        </Card>
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Recent activity</CardTitle>
          <CardDescription>
            <Link to="/customer/history" className="underline underline-offset-4">
              See all transactions
            </Link>
          </CardDescription>
        </CardHeader>
        <CardContent>
          {recent.isPending && <p className="text-sm text-muted-foreground">Loading…</p>}
          {recent.isError && <ErrorAlert error={recent.error} title="Couldn't load recent activity" />}
          {recent.isSuccess &&
            (recent.data.items.length === 0 ? (
              <p className="text-sm text-muted-foreground">No transactions yet.</p>
            ) : (
              <TransactionTable transactions={recent.data.items} caption="Recent transactions" />
            ))}
        </CardContent>
      </Card>
    </>
  )
}

function Amount({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Card className="gap-2 py-4">
      <CardHeader className="px-4">
        <CardDescription>{label}</CardDescription>
      </CardHeader>
      <CardContent className="px-4">
        <p className="text-2xl font-semibold tabular-nums" data-testid={`amount-${label.toLowerCase()}`}>
          {formatRupees(value)}
        </p>
        {hint !== undefined && <p className="text-xs text-muted-foreground">{hint}</p>}
      </CardContent>
    </Card>
  )
}
