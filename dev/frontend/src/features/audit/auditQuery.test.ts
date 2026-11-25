import { readAlertFilters } from '../aml/filters'
import { toAuditQuery } from './auditQuery'

describe('toAuditQuery', () => {
  it('turns India-time days into the instants the API filters by (to is exclusive)', () => {
    const { query, problems } = toAuditQuery(
      new URLSearchParams('from=2026-11-24&to=2026-11-24&entityType=wallet&action=WALLET_FREEZE&page=2'),
    )
    expect(problems).toEqual({})
    expect(query).toEqual({
      entityType: 'WALLET',
      entityId: undefined,
      actorId: undefined,
      action: 'WALLET_FREEZE',
      from: '2026-11-23T18:30:00.000Z',
      to: '2026-11-24T18:30:00.000Z',
      page: 1,
      size: 20,
    })
  })

  it('reports bad IDs and a reversed range instead of sending them', () => {
    const { query, problems } = toAuditQuery(new URLSearchParams('entityId=123&from=2026-11-25&to=2026-11-24'))
    expect(problems.entityId).toBe('Enter a full ID (a UUID).')
    expect(problems.from).toBe('"From" must not be after "To".')
    expect(query.entityId).toBeUndefined()
  })
})

describe('readAlertFilters', () => {
  it('keeps known values and drops the rest', () => {
    expect(
      readAlertFilters(new URLSearchParams('rule=R2&severity=HIGH&wallet=00000000-0000-7000-8000-00000000A001&page=3')),
    ).toEqual({ ruleCode: 'R2', severity: 'HIGH', walletId: '00000000-0000-7000-8000-00000000a001', page: 2 })
    expect(readAlertFilters(new URLSearchParams('rule=R9&severity=LOW&wallet=x&page=-1'))).toEqual({
      ruleCode: undefined,
      severity: undefined,
      walletId: undefined,
      page: 0,
    })
  })
})
