import type { Role } from '@/auth/roles'

export interface NavItem {
  label: string
  to: string
  /** Shown to a user holding any of these roles. */
  roles: readonly Role[]
  /** Planned screen (a later F day): listed, not yet a link. */
  soon?: boolean
}

/** Navigation per actor (SPEC §2). Convenience only: the backend enforces every role (CLAUDE.md "Frontend"). */
export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Dashboard', to: '/customer', roles: ['CUSTOMER'] },
  { label: 'KYC', to: '/customer/kyc', roles: ['CUSTOMER'] },
  { label: 'Add money', to: '/customer/add-money', roles: ['CUSTOMER'] },
  { label: 'Send money', to: '/customer/send', roles: ['CUSTOMER'] },
  { label: 'History', to: '/customer/history', roles: ['CUSTOMER'] },
  { label: 'Privacy', to: '/customer/privacy', roles: ['CUSTOMER'] },

  { label: 'Compliance', to: '/compliance', roles: ['COMPLIANCE_OFFICER'] },
  { label: 'KYC queue', to: '/compliance/kyc', roles: ['COMPLIANCE_OFFICER'] },
  { label: 'Held transfers', to: '/compliance/transfers', roles: ['COMPLIANCE_OFFICER'] },
  { label: 'Wallets', to: '/compliance/wallets', roles: ['COMPLIANCE_OFFICER'] },
  { label: 'AML alerts', to: '/compliance/aml', roles: ['COMPLIANCE_OFFICER'] },
  { label: 'AML cases', to: '/compliance/aml/cases', roles: ['COMPLIANCE_OFFICER'] },

  { label: 'Admin', to: '/admin', roles: ['ADMIN'] },
  { label: 'Reconciliation', to: '/admin/reconciliation', roles: ['ADMIN'] },

  { label: 'Audit', to: '/audit', roles: ['AUDITOR'] },
  { label: 'Audit log', to: '/audit/events', roles: ['AUDITOR'] },
]

export function navFor(roles: readonly Role[]): NavItem[] {
  return NAV_ITEMS.filter((item) => item.roles.some((role) => roles.includes(role)))
}
