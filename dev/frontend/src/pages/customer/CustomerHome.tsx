import { SectionHome } from '../SectionHome'

export function CustomerHome() {
  return (
    <SectionHome
      title="Customer dashboard"
      intro="Your TrustLedger wallet: KYC, balance, add money and send money."
      upcoming={[
        'KYC onboarding (minimum and full, with document upload)',
        'Wallet dashboard: balance, held, available, tier and limits',
        'Add money (Razorpay test checkout) and send money',
        'Transaction history, statements and privacy requests',
      ]}
    />
  )
}
