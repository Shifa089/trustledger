import { SectionHome } from '../SectionHome'

export function AuditHome() {
  return (
    <SectionHome
      title="Audit"
      intro="Read-only access to the tamper-evident audit trail."
      upcoming={['Audit log viewer with filters', '"Verify chain": walk the hash chain and show the first broken link']}
    />
  )
}
