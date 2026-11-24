/**
 * Build-time settings from VITE_* variables (see .env.example). They end up in the browser bundle, so none of them
 * is a secret: trustledger-web is a public OIDC client.
 */
export interface AppConfig {
  coreApiUrl: string
  amlApiUrl: string
  oidcAuthority: string
  oidcClientId: string
  githubUrl: string | undefined
  videoUrl: string | undefined
}

/**
 * `npm run dev` works out of the box against the local stack (infra/docker-compose.yml, both apps with the local
 * profile). A production build has no defaults: a missing value fails at startup instead of calling localhost.
 */
const LOCAL_DEFAULTS: Partial<Record<string, string>> = {
  VITE_CORE_API_URL: 'http://localhost:8080',
  VITE_AML_API_URL: 'http://localhost:8081',
  VITE_OIDC_AUTHORITY: 'http://localhost:8180/realms/trustledger',
  VITE_OIDC_CLIENT_ID: 'trustledger-web',
}

function required(name: string, value: string | undefined): string {
  const given = value === undefined || value.trim() === '' ? undefined : value
  const resolved = given ?? (import.meta.env.DEV ? LOCAL_DEFAULTS[name] : undefined)
  if (resolved === undefined) {
    throw new Error(`${name} is not set; copy frontend/.env.example to frontend/.env.local`)
  }
  return resolved.trim().replace(/\/+$/, '')
}

function optional(value: string | undefined): string | undefined {
  return value === undefined || value.trim() === '' ? undefined : value.trim()
}

export const config: AppConfig = {
  coreApiUrl: required('VITE_CORE_API_URL', import.meta.env.VITE_CORE_API_URL),
  amlApiUrl: required('VITE_AML_API_URL', import.meta.env.VITE_AML_API_URL),
  oidcAuthority: required('VITE_OIDC_AUTHORITY', import.meta.env.VITE_OIDC_AUTHORITY),
  oidcClientId: required('VITE_OIDC_CLIENT_ID', import.meta.env.VITE_OIDC_CLIENT_ID),
  githubUrl: optional(import.meta.env.VITE_GITHUB_URL),
  videoUrl: optional(import.meta.env.VITE_VIDEO_URL),
}
