import { Link } from 'react-router'
import { useKycOverview } from '@/api/kyc'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDateTime } from '@/lib/dates'
import { DemoDataNotice } from './DemoDataNotice'
import { nextStep, STATUS_TEXT, type KycNextStep } from './kycState'

const LEVEL_TEXT = { NONE: 'Not started', MIN: 'Minimum KYC', FULL: 'Full KYC' } as const

/** FR-KYC-1..3 from the customer's side: the current level, every case, and the next step. */
export function KycStatusPage() {
  const overview = useKycOverview()

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">KYC</h1>
        <p className="text-muted-foreground">
          Minimum KYC gives you a wallet. Full KYC raises your limits and lets you send money.
        </p>
      </div>
      <DemoDataNotice />
      {overview.isPending && <p className="text-sm text-muted-foreground">Loading your KYC status…</p>}
      {overview.isError && <ErrorAlert error={overview.error} title="Couldn't load your KYC status" />}
      {overview.isSuccess && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Current level</CardTitle>
              <CardDescription>{LEVEL_TEXT[overview.data.currentLevel]}</CardDescription>
            </CardHeader>
            <CardContent>
              <NextStep next={nextStep(overview.data)} />
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Your KYC cases</CardTitle>
            </CardHeader>
            <CardContent>
              {overview.data.cases.length === 0 ? (
                <p className="text-sm text-muted-foreground">No KYC submitted yet.</p>
              ) : (
                <ul className="flex flex-col divide-y">
                  {overview.data.cases.map((kycCase) => (
                    <li key={kycCase.id} className="flex flex-wrap items-center justify-between gap-2 py-3 text-sm">
                      <div>
                        <p className="font-medium">{kycCase.level === 'MIN' ? 'Minimum KYC' : 'Full KYC'}</p>
                        <p className="text-muted-foreground">
                          Submitted {formatDateTime(kycCase.createdAt)}
                          {kycCase.maskedPan !== null && <> · PAN {kycCase.maskedPan}</>}
                        </p>
                        {kycCase.rejectionReason !== null && (
                          <p className="text-destructive">Reason: {kycCase.rejectionReason}</p>
                        )}
                      </div>
                      <Badge variant={kycCase.status === 'REJECTED' ? 'destructive' : 'secondary'}>
                        {STATUS_TEXT[kycCase.status]}
                      </Badge>
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}

function NextStep({ next }: { next: KycNextStep }) {
  switch (next.step) {
    case 'min':
      return (
        <div className="flex flex-col items-start gap-3 text-sm">
          <p>Start with minimum KYC: your name, date of birth, mobile number and PAN.</p>
          <Button asChild>
            <Link to="/customer/kyc/min">Start minimum KYC</Link>
          </Button>
        </div>
      )
    case 'full':
      return (
        <div className="flex flex-col items-start gap-3 text-sm">
          <p>You have a wallet. Complete full KYC (address and documents) to send money and raise your limits.</p>
          <Button asChild>
            <Link to="/customer/kyc/full">Start full KYC</Link>
          </Button>
        </div>
      )
    case 'upload':
      return (
        <div className="flex flex-col items-start gap-3 text-sm">
          <p>Your full KYC is submitted. Upload your documents so an officer can review it.</p>
          <Button asChild>
            <Link to="/customer/kyc/full">Upload documents</Link>
          </Button>
        </div>
      )
    case 'review':
      return <p className="text-sm">Your documents were verified. A second officer will approve them soon.</p>
    case 'rejected':
      return (
        <div className="flex flex-col items-start gap-3 text-sm">
          <p>
            Your full KYC was rejected
            {next.fullCase.rejectionReason === null ? '.' : `: ${next.fullCase.rejectionReason}`}
          </p>
          <Button asChild variant="outline">
            <Link to="/customer/kyc/full">Submit full KYC again</Link>
          </Button>
        </div>
      )
    case 'done':
      return <p className="text-sm">Full KYC is approved. All wallet features are open to you.</p>
  }
}
