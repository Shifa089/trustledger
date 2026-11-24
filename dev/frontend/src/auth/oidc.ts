import { InMemoryWebStorage, UserManager, WebStorageStateStore, type UserManagerSettings } from 'oidc-client-ts'
import { config } from '@/config'

/**
 * OIDC with Keycloak client trustledger-web: Authorization Code + PKCE (S256, oidc-client-ts's default for the code
 * flow). A public client has no secret; PKCE proves that whoever redeems the code is the app that asked for it.
 *
 * Tokens (access, ID, refresh) live in memory only (`userStore` = InMemoryWebStorage), never in localStorage or
 * sessionStorage, where any injected script could read them. The cost: a full page reload forgets the tokens, and
 * the app asks Keycloak again; Keycloak's own SSO cookie makes that a quick redirect without a password.
 *
 * `stateStore` is the one use of sessionStorage: during the redirect to Keycloak the page is unloaded, so the
 * PKCE code_verifier and the `state` value must survive it. They hold no token and are deleted on the callback.
 */
export function oidcSettings(origin: string = window.location.origin): UserManagerSettings {
  return {
    authority: config.oidcAuthority,
    client_id: config.oidcClientId,
    redirect_uri: `${origin}/callback`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: 'openid profile',
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    // eslint-disable-next-line no-restricted-globals -- PKCE verifier and state only, never a token (see above)
    stateStore: new WebStorageStateStore({ store: sessionStorage }),
    // Silent renew: shortly before the 5-minute access token expires, oidc-client-ts redeems the refresh token
    // (kept in memory with the rest) for a new one. No hidden iframe, no third-party cookies needed.
    automaticSilentRenew: true,
    // Keycloak's session iframe needs third-party cookies; logout elsewhere is noticed on the next renew instead.
    monitorSession: false,
    loadUserInfo: false,
  }
}

export const userManager = new UserManager(oidcSettings())

/** Where to go after the callback; set by RequireAuth when it starts a sign-in. */
export interface SigninState {
  returnTo?: string
}

/** The current access token for the API client, or undefined when signed out or expired. */
export async function currentAccessToken(): Promise<string | undefined> {
  const user = await userManager.getUser()
  return user !== null && !user.expired ? user.access_token : undefined
}
