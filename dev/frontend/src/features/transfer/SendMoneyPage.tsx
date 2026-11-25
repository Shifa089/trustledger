import { Copy } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { isNoWallet, useMyWallet } from '@/api/wallet'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { SendMoneyForm } from './SendMoneyForm'

/** /customer/send: send money (FR-P-1..3), and the wallet ID others need to send money here. */
export function SendMoneyPage() {
  const wallet = useMyWallet()
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Send money</h1>
        <p className="text-muted-foreground">Send money to another TrustLedger wallet by mobile number or wallet ID.</p>
      </div>
      {wallet.isPending && <p className="text-sm text-muted-foreground">Loading your wallet…</p>}
      {wallet.isError &&
        (isNoWallet(wallet.error) ? (
          <NeedsKyc text="Complete KYC to get a wallet first." />
        ) : (
          <ErrorAlert error={wallet.error} title="Couldn't load your wallet" />
        ))}
      {wallet.isSuccess &&
        (wallet.data.status !== 'ACTIVE' ? (
          <Alert variant="destructive">
            <AlertTitle>{wallet.data.status === 'FROZEN' ? 'Wallet frozen' : 'Wallet closed'}</AlertTitle>
            <AlertDescription>This wallet cannot send or receive money.</AlertDescription>
          </Alert>
        ) : !wallet.data.limits.p2pAllowed ? (
          <NeedsKyc text="Sending money needs full KYC." />
        ) : (
          <SendMoneyForm wallet={wallet.data} />
        ))}
      {wallet.isSuccess && <WalletIdCard walletId={wallet.data.walletId} />}
    </div>
  )
}

function NeedsKyc({ text }: { text: string }) {
  return (
    <Card className="max-w-lg">
      <CardHeader>
        <CardTitle>{text}</CardTitle>
        <CardDescription>Full KYC adds your address and documents; a compliance officer then approves it.</CardDescription>
      </CardHeader>
      <CardContent>
        <Button asChild>
          <Link to="/customer/kyc">Go to KYC</Link>
        </Button>
      </CardContent>
    </Card>
  )
}

/** Your wallet ID, to give to someone who wants to send you money. Not personal data: it is a random UUID. */
function WalletIdCard({ walletId }: { walletId: string }) {
  const [copied, setCopied] = useState(false)
  const clipboard = typeof navigator === 'undefined' ? undefined : (navigator.clipboard as Clipboard | undefined)
  return (
    <Card className="max-w-lg">
      <CardHeader>
        <CardTitle>Receive money</CardTitle>
        <CardDescription>Share your wallet ID with someone who wants to send you money.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-center gap-3">
        <code className="rounded bg-muted px-2 py-1 text-sm break-all" data-testid="my-wallet-id">
          {walletId}
        </code>
        {clipboard !== undefined && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              void clipboard.writeText(walletId).then(() => {
                setCopied(true)
              })
            }}
          >
            <Copy aria-hidden />
            {copied ? 'Copied' : 'Copy wallet ID'}
          </Button>
        )}
      </CardContent>
    </Card>
  )
}
