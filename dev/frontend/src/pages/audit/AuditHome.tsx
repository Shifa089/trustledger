import { SectionHome } from '../SectionHome'

export function AuditHome() {
  return (
    <SectionHome
      title="Audit"
      intro="Read-only access to the tamper-evident audit trail."
      links={[
        {
          to: '/audit/events',
          label: 'Audit log',
          description: 'Every state-changing action with filters, and "Verify chain" to check the hash chain.',
        },
      ]}
    />
  )
}
