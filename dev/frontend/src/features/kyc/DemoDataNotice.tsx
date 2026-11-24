import { FlaskConical } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'

/** On every KYC screen: this is a portfolio demo, so nobody should type real personal data (CLAUDE.md). */
export function DemoDataNotice() {
  return (
    <Alert role="note" aria-label="Demo data notice">
      <FlaskConical aria-hidden />
      <AlertTitle>Use fake data, this is a demo</AlertTitle>
      <AlertDescription>
        <p>
          Never enter your real PAN, mobile number, address or documents. Make up values that pass the format checks,
          for example PAN <span className="font-mono">ABCPE1234F</span> and mobile{' '}
          <span className="font-mono">9876543210</span>, and upload a blank PDF or image.
        </p>
      </AlertDescription>
    </Alert>
  )
}
