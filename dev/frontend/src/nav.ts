import type { Role } from '@/auth/roles'

export interface NavItem {
  label: string
  to: string
  /** Shown to a user holding any of these roles. */
  roles: readonly Role[]
  /** Planned screen (later F days): listed, not yet a link. */
  soon?: boolean
}

/** Navigation per actor (SPEC §2). The screens behind `soon` items arrive in Days 30-33. */
export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Dashboard', to: '/customer', roles: ['CUSTOMER'] },
  { label: 'KYC', to: '/customer/kyc', roles: ['CUSTOMER'], soon: true },
  { label: 'Add money', to: '/customer/add-money', roles: ['CUSTOMER'], soon: true },
  { label: 'Send money', to: '/customer/send', roles: ['CUSTOMER'], soon: true },
  { label: 'History', to: '/customer/history', roles: ['CUSTOMER'], soon: true },
  { label: 'Privacy', to: '/customer/privacy', roles: ['CUSTOMER'], soon: true },

  { label: 'Compliance', to: '/compliance', roles: ['COMPLIANCE_OFFICER'] },
  { label: 'KYC queue', to: '/compliance/kyc', roles: ['COMPLIANCE_OFFICER'], soon: true },
  { label: 'Held transfers', to: '/compliance/transfers', roles: ['COMPLIANCE_OFFICER'], soon: true },
  { label: 'AML cases', to: '/compliance/aml', roles: ['COMPLIANCE_OFFICER'], soon: true },

  { label: 'Admin', to: '/admin', roles: ['ADMIN'] },
  { label: 'Reconciliation', to: '/admin/reconciliation', roles: ['ADMIN'], soon: true },

  { label: 'Audit', to: '/audit', roles: ['AUDITOR'] },
  { label: 'Audit log', to: '/audit/events', roles: ['AUDITOR'], soon: true },
]

export function navFor(roles: readonly Role[]): NavItem[] {
  return NAV_ITEMS.filter((item) => item.roles.some((role) => roles.includes(role)))
}
