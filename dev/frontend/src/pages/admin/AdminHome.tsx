import { SectionHome } from '../SectionHome'

export function AdminHome() {
  return (
    <SectionHome
      title="Admin"
      intro="Operations: reconciliation and ledger account balances."
      upcoming={['Run reconciliation and see the result', 'Ledger-derived account balances']}
    />
  )
}
