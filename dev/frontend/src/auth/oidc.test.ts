import { User, UserManager } from 'oidc-client-ts'
import { oidcSettings } from './oidc'

describe('OIDC settings', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('uses Authorization Code with the trustledger-web client and our callback', () => {
    const settings = oidcSettings('http://localhost:5173')

    expect(settings.response_type).toBe('code')
    expect(settings.client_id).toBe('trustledger-web')
    expect(settings.authority).toBe('http://keycloak.test/realms/trustledger')
    expect(settings.redirect_uri).toBe('http://localhost:5173/callback')
    expect(settings.post_logout_redirect_uri).toBe('http://localhost:5173/')
    expect(settings.automaticSilentRenew).toBe(true)
    expect(settings).not.toHaveProperty('client_secret')
    expect(settings.disablePKCE).toBeFalsy()
  })

  it('keeps tokens in memory only, never in localStorage or sessionStorage', async () => {
    const manager = new UserManager(oidcSettings('http://localhost:5173'))
    await manager.storeUser(
      new User({
        access_token: 'secret-access-token',
        refresh_token: 'secret-refresh-token',
        id_token: 'secret-id-token',
        token_type: 'Bearer',
        profile: { sub: 'user-1', iss: 'http://keycloak.test/realms/trustledger', aud: 'trustledger-web', exp: 0, iat: 0 },
      }),
    )

    expect((await manager.getUser())?.access_token).toBe('secret-access-token')
    const stored = JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)])
    expect(stored).not.toContain('secret-')
  })
})
