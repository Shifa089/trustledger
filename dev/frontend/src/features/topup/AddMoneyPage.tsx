import { Link } from 'react-router'
import { isNoWallet, useLimitUsage, useMyWallet } from '@/api/wallet'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { roomLeft } from './room'
import { TopupFlow, type TopupFlowProps } from './TopupFlow'

/** /customer/add-money (FR-P-4). `flow` lets tests swap in a fake Checkout and short polling. */
export function AddMoneyPage({ flow = {} }: { flow?: Omit<TopupFlowProps, 'roomLeft'> }) {
  const wallet = useMyWallet()
  const usage = useLimitUsage(wallet.isSuccess)
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Add money</h1>
        <p className="text-muted-foreground">
          Pay with Razorpay in test mode: no real money moves. Use Razorpay's test cards or UPI IDs.
        </p>
      </div>
      {wallet.isPending && <p className="text-sm text-muted-foreground">Loading your wallet…</p>}
      {wallet.isError &&
        (isNoWallet(wallet.error) ? (
          <Card className="max-w-lg">
            <CardHeader>
              <CardTitle>No wallet yet</CardTitle>
              <CardDescription>Complete minimum KYC to get a wallet, then add money.</CardDescription>
            </CardHeader>
            <CardContent>
              <Button asChild>
                <Link to="/customer/kyc">Complete KYC</Link>
              </Button>
            </CardContent>
          </Card>
        ) : (
          <ErrorAlert error={wallet.error} title="Couldn't load your wallet" />
        ))}
      {wallet.isSuccess &&
        (wallet.data.status === 'ACTIVE' ? (
          <TopupFlow {...flow} roomLeft={roomLeft(wallet.data, usage.data)} />
        ) : (
          <Alert variant="destructive">
            <AlertTitle>{wallet.data.status === 'FROZEN' ? 'Wallet frozen' : 'Wallet closed'}</AlertTitle>
            <AlertDescription>This wallet cannot receive money.</AlertDescription>
          </Alert>
        ))}
    </div>
  )
}
