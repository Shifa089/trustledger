import { SectionHome } from '../SectionHome'

export function AdminHome() {
  return (
    <SectionHome
      title="Admin"
      intro="Operations: reconciliation of wallet balances against the ledger."
      links={[
        {
          to: '/admin/reconciliation',
          label: 'Reconciliation',
          description: 'Run the nightly balance check now and see any mismatch.',
        },
      ]}
    />
  )
}
