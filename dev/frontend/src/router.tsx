import type { RouteObject } from 'react-router'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireRole } from '@/auth/RequireRole'
import { AppLayout } from '@/layouts/AppLayout'
import { PublicLayout } from '@/layouts/PublicLayout'
import { RootLayout } from '@/layouts/RootLayout'
import { AdminHome } from '@/pages/admin/AdminHome'
import { AuditHome } from '@/pages/audit/AuditHome'
import { ComplianceHome } from '@/pages/compliance/ComplianceHome'
import { CustomerHome } from '@/pages/customer/CustomerHome'
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
                children: [{ index: true, element: <CustomerHome /> }],
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
