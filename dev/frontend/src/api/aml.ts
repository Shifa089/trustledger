import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { amlApi } from './index'
import type { Page } from './types'

/**
 * risk-aml-service (SPEC §5 "AML service"): alerts, cases and the case workflow (FR-AML-2/4). This service knows an
 * officer by their Keycloak subject (`assignedTo`), not by core-app's user ID.
 */

export type Severity = 'MEDIUM' | 'HIGH'
export const SEVERITIES: readonly Severity[] = ['MEDIUM', 'HIGH']
export type CaseStatus = 'OPEN' | 'INVESTIGATING' | 'CLOSED_FALSE_POSITIVE' | 'ESCALATED'
export const CASE_STATUSES: readonly CaseStatus[] = ['OPEN', 'INVESTIGATING', 'CLOSED_FALSE_POSITIVE', 'ESCALATED']

/** The AML rules (SPEC §2 "AML rules"). */
export const RULES: Readonly<Record<string, string>> = {
  R1: 'R1 Velocity',
  R2: 'R2 Structuring',
  R3: 'R3 Large amount',
  R4: 'R4 Fan-out',
}

export const AML_PAGE_SIZE = 20

/** AmlCase.MAX_NOTES_LENGTH. */
export const MAX_NOTES_LENGTH = 2000

export interface AlertSummary {
  id: string
  caseId: string | null
  walletId: string
  ruleCode: string
  severity: Severity
  details: string | null
  createdAt: string
}

export interface CaseSummary {
  id: string
  walletId: string
  status: CaseStatus
  assignedTo: string | null
  alertCount: number
  highestSeverity: Severity | null
  createdAt: string
  updatedAt: string
}

export interface CaseAlert {
  id: string
  ruleCode: string
  severity: Severity
  details: string | null
  createdAt: string
}

export interface FreezeRequestItem {
  id: string
  alertId: string
  ruleCode: string
  requestedAt: string
  publishedAt: string | null
}

export interface FactItem {
  id: string
  direction: 'OUT' | 'IN'
  source: 'TRANSFER' | 'TOPUP'
  counterpartyWalletId: string | null
  referenceId: string
  amount: string
  currency: string
  occurredAt: string
}

export interface CaseDetail {
  id: string
  walletId: string
  status: CaseStatus
  assignedTo: string | null
  resolution: string | null
  createdAt: string
  updatedAt: string
  alerts: CaseAlert[]
  freezeRequests: FreezeRequestItem[]
  facts: FactItem[]
}

export interface AlertFilters {
  ruleCode?: string | undefined
  severity?: Severity | undefined
  walletId?: string | undefined
  page: number
}

export interface CaseFilters {
  status?: CaseStatus | undefined
  walletId?: string | undefined
  page: number
}

export const amlKeys = {
  alerts: (filters: AlertFilters) => ['aml', 'alerts', filters] as const,
  casesAll: ['aml', 'cases'] as const,
  cases: (filters: CaseFilters) => ['aml', 'cases', filters] as const,
  case: (id: string) => ['aml', 'case', id] as const,
}

export function useAlerts(filters: AlertFilters) {
  return useQuery({
    queryKey: amlKeys.alerts(filters),
    queryFn: () => amlApi.get<Page<AlertSummary>>('/aml/alerts', { query: { ...filters, size: AML_PAGE_SIZE } }),
    placeholderData: keepPreviousData,
  })
}

export function useCases(filters: CaseFilters) {
  return useQuery({
    queryKey: amlKeys.cases(filters),
    queryFn: () => amlApi.get<Page<CaseSummary>>('/aml/cases', { query: { ...filters, size: AML_PAGE_SIZE } }),
    placeholderData: keepPreviousData,
  })
}

export function useCase(id: string) {
  return useQuery({
    queryKey: amlKeys.case(id),
    queryFn: () => amlApi.get<CaseDetail>(`/aml/cases/${encodeURIComponent(id)}`),
  })
}

export type CaseAction =
  | { action: 'assign' }
  | { action: 'close'; notes: string }
  | { action: 'escalate'; notes: string }

function bodyOf(action: CaseAction): unknown {
  switch (action.action) {
    case 'assign':
      return {} // no assigneeId: the caller takes the case
    case 'close':
      return { resolution: 'FALSE_POSITIVE', notes: action.notes }
    case 'escalate':
      return { notes: action.notes }
  }
}

export function useCaseAction(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (action: CaseAction) =>
      amlApi.post<CaseDetail>(`/aml/cases/${encodeURIComponent(id)}/${action.action}`, bodyOf(action)),
    onSuccess: (updated) => {
      queryClient.setQueryData(amlKeys.case(id), updated)
      void queryClient.invalidateQueries({ queryKey: amlKeys.casesAll })
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: amlKeys.case(id) })
    },
  })
}

/** GET /aml/cases/{id}/str-report: the CSV and the server's file name (the AML service exposes Content-Disposition). */
export function downloadStrReport(id: string) {
  return amlApi.download(`/aml/cases/${encodeURIComponent(id)}/str-report`)
}
