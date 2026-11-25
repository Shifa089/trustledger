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
import { PrivacyPage } from '@/features/privacy/PrivacyPage'
import { AddMoneyPage } from '@/features/topup/AddMoneyPage'
import { SendMoneyPage } from '@/features/transfer/SendMoneyPage'
import { WalletDashboard } from '@/features/wallet/WalletDashboard'
import { ReconciliationPage } from '@/features/admin/ReconciliationPage'
import { AmlAlertsPage } from '@/features/aml/AmlAlertsPage'
import { AmlCasePage } from '@/features/aml/AmlCasePage'
import { AmlCasesPage } from '@/features/aml/AmlCasesPage'
import { AuditEventsPage } from '@/features/audit/AuditEventsPage'
import { HeldTransfersPage } from '@/features/compliance/HeldTransfersPage'
import { KycQueuePage } from '@/features/compliance/KycQueuePage'
import { KycReviewPage } from '@/features/compliance/KycReviewPage'
import { WalletPage } from '@/features/compliance/WalletPage'
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
                  { path: 'add-money', element: <AddMoneyPage /> },
                  { path: 'send', element: <SendMoneyPage /> },
                  { path: 'privacy', element: <PrivacyPage /> },
                ],
              },
              {
                path: 'compliance',
                element: <RequireRole roles={['COMPLIANCE_OFFICER']} />,
                children: [
                  { index: true, element: <ComplianceHome /> },
                  { path: 'kyc', element: <KycQueuePage /> },
                  { path: 'kyc/:id', element: <KycReviewPage /> },
                  { path: 'transfers', element: <HeldTransfersPage /> },
                  { path: 'wallets', element: <WalletPage /> },
                  { path: 'wallets/:id', element: <WalletPage /> },
                  { path: 'aml', element: <AmlAlertsPage /> },
                  { path: 'aml/cases', element: <AmlCasesPage /> },
                  { path: 'aml/cases/:id', element: <AmlCasePage /> },
                ],
              },
              {
                path: 'admin',
                element: <RequireRole roles={['ADMIN']} />,
                children: [
                  { index: true, element: <AdminHome /> },
                  { path: 'reconciliation', element: <ReconciliationPage /> },
                ],
              },
              {
                path: 'audit',
                element: <RequireRole roles={['AUDITOR']} />,
                children: [
                  { index: true, element: <AuditHome /> },
                  { path: 'events', element: <AuditEventsPage /> },
                ],
              },
            ],
          },
        ],
      },
    ],
  },
]
