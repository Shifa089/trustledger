import { RULES, type CaseDetail } from '@/api/aml'
import { formatRupees } from '@/lib/money'

/** One line of a case's timeline: money moved, a rule fired, or a freeze was requested or sent to core-app. */
export interface TimelineEntry {
  key: string
  at: string
  kind: 'fact' | 'alert' | 'freeze-requested' | 'freeze-published'
  title: string
  detail?: string
  severity?: 'MEDIUM' | 'HIGH'
}

/** Entries at the same instant: the fact, then its alert, then the freeze it caused. */
const ORDER: Readonly<Record<TimelineEntry['kind'], number>> = {
  fact: 0,
  alert: 1,
  'freeze-requested': 2,
  'freeze-published': 3,
}

export function ruleName(code: string): string {
  return RULES[code] ?? code
}

/**
 * The case's facts, alerts and freeze requests in time order (oldest first), so an officer reads what the customer
 * did and which rule reacted to it.
 */
export function buildTimeline(c: CaseDetail): TimelineEntry[] {
  const entries: TimelineEntry[] = []
  for (const fact of c.facts) {
    const what = fact.source === 'TOPUP' ? 'Top-up' : fact.direction === 'OUT' ? 'Transfer sent' : 'Transfer received'
    const entry: TimelineEntry = {
      key: `fact-${fact.id}`,
      at: fact.occurredAt,
      kind: 'fact',
      title: `${what} ${formatRupees(fact.amount)}`,
    }
    if (fact.counterpartyWalletId !== null) {
      entry.detail = `${fact.direction === 'OUT' ? 'to' : 'from'} wallet ${fact.counterpartyWalletId.slice(0, 8)}…`
    }
    entries.push(entry)
  }
  for (const alert of c.alerts) {
    const entry: TimelineEntry = {
      key: `alert-${alert.id}`,
      at: alert.createdAt,
      kind: 'alert',
      title: `Alert: ${ruleName(alert.ruleCode)}`,
      severity: alert.severity,
    }
    if (alert.details !== null) entry.detail = alert.details
    entries.push(entry)
  }
  for (const freeze of c.freezeRequests) {
    entries.push({
      key: `freeze-${freeze.id}`,
      at: freeze.requestedAt,
      kind: 'freeze-requested',
      title: `Freeze requested (${ruleName(freeze.ruleCode)})`,
    })
    if (freeze.publishedAt !== null) {
      entries.push({
        key: `freeze-sent-${freeze.id}`,
        at: freeze.publishedAt,
        kind: 'freeze-published',
        title: 'Freeze request sent to core-app',
      })
    }
  }
  return entries.sort(
    (a, b) => new Date(a.at).getTime() - new Date(b.at).getTime() || ORDER[a.kind] - ORDER[b.kind],
  )
}
