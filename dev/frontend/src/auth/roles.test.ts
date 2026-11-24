import { fakeAccessToken } from '@/test/render'
import { decodeJwtPayload, homePathFor, rolesFromAccessToken } from './roles'

describe('roles', () => {
  it('reads realm_access.roles and drops roles the app does not know', () => {
    const token = fakeAccessToken(['offline_access', 'AUDITOR', 'default-roles-trustledger', 'CUSTOMER'])
    expect(rolesFromAccessToken(token)).toEqual(['CUSTOMER', 'AUDITOR'])
  })

  it.each([undefined, '', 'not-a-jwt', 'a.!!!.c', `x.${btoa('"just a string"')}.y`])(
    'returns no roles for %j',
    (token) => {
      expect(rolesFromAccessToken(token)).toEqual([])
    },
  )

  it('decodes base64url payloads with non-ASCII text', () => {
    const payload = btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify({ name: 'Ünïcødé ₹' }))))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '')
    expect(decodeJwtPayload(`h.${payload}.s`)).toEqual({ name: 'Ünïcødé ₹' })
  })

  it.each([
    [['CUSTOMER'], '/customer'],
    [['COMPLIANCE_OFFICER'], '/compliance'],
    [['ADMIN'], '/admin'],
    [['AUDITOR'], '/audit'],
    [['AUDITOR', 'COMPLIANCE_OFFICER'], '/compliance'],
    [[], '/forbidden'],
  ] as const)('sends %j home to %s', (roles, path) => {
    expect(homePathFor(roles)).toBe(path)
  })
})
