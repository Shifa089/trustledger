import type { RouteObject } from 'react-router'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireRole } from '@/auth/RequireRole'
import { AppLayout } from '@/layouts/AppLayout'
import { PublicLayout } from '@/layouts/PublicLayout'
import { RootLayout } from '@/layouts/RootLayout'
import { FullKycPage } from '@/features/kyc/FullKycPage'
import { KycStatusPage } from '@/features/kyc/KycStatusPage'
import { MinKycPage } from '@/features/kyc/MinKycPage'
import { TransactionHistory } from '@/features/history/TransactionHistory'
import { WalletDashboard } from '@/features/wallet/WalletDashboard'
import { AdminHome } from '@/pages/admin/AdminHome'
import { AuditHome } from '@/pages/audit/AuditHome'
import { ComplianceHome } from '@/pages/compliance/ComplianceHome'
import { LandingPage } from '@/pages/LandingPage'
import { AppHomeRedirect, CallbackPage, ForbiddenPage, NotFoundPage } from '@/pages/StatusPages'

/**
 * Public: the landing page and the OIDC callback. Everything else needs a signed-in user (RequireAuth) and each
 * section its role (RequireRole), mirroring the role column of SPEC §5.
 */
export const routes: RouteObject[] = [
  {
    element: <RootLayout />,
    children: [
      {
        element: <PublicLayout />,
        children: [
          { index: true, element: <LandingPage /> },
          { path: 'callback', element: <CallbackPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
      {
        element: <RequireAuth />,
        children: [
          {
            element: <AppLayout />,
            children: [
              { path: 'app', element: <AppHomeRedirect /> },
              { path: 'forbidden', element: <ForbiddenPage /> },
              {
                path: 'customer',
                element: <RequireRole roles={['CUSTOMER']} />,
                children: [
                  { index: true, element: <WalletDashboard /> },
                  { path: 'kyc', element: <KycStatusPage /> },
                  { path: 'kyc/min', element: <MinKycPage /> },
                  { path: 'kyc/full', element: <FullKycPage /> },
                  { path: 'history', element: <TransactionHistory /> },
                ],
              },
              {
                path: 'compliance',
                element: <RequireRole roles={['COMPLIANCE_OFFICER']} />,
                children: [{ index: true, element: <ComplianceHome /> }],
              },
              {
                path: 'admin',
                element: <RequireRole roles={['ADMIN']} />,
                children: [{ index: true, element: <AdminHome /> }],
              },
              {
                path: 'audit',
                element: <RequireRole roles={['AUDITOR']} />,
                children: [{ index: true, element: <AuditHome /> }],
              },
            ],
          },
        ],
      },
    ],
  },
]
