import { Link, Navigate, useNavigate } from 'react-router'
import { useKycOverview } from '@/api/kyc'
import { ErrorAlert } from '@/components/ErrorAlert'
import { DemoDataNotice } from './DemoDataNotice'
import { MinKycForm } from './MinKycForm'

/** /customer/kyc/min. Someone already at MIN or FULL goes back to the status page. */
export function MinKycPage() {
  const overview = useKycOverview()
  const navigate = useNavigate()

  if (overview.isSuccess && overview.data.currentLevel !== 'NONE') {
    return <Navigate to="/customer/kyc" replace />
  }
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Minimum KYC</h1>
        <p className="text-muted-foreground">
          Valid details give you a wallet straight away (the OTP check is simulated in this demo).{' '}
          <Link to="/customer/kyc" className="underline underline-offset-4">
            Back to KYC status
          </Link>
        </p>
      </div>
      <DemoDataNotice />
      {overview.isError && <ErrorAlert error={overview.error} title="Couldn't load your KYC status" />}
      <MinKycForm
        onDone={() => {
          void navigate('/customer', { state: { justOnboarded: true } })
        }}
      />
    </div>
  )
}
