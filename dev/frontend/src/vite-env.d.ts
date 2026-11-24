/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CORE_API_URL?: string
  readonly VITE_AML_API_URL?: string
  readonly VITE_OIDC_AUTHORITY?: string
  readonly VITE_OIDC_CLIENT_ID?: string
  readonly VITE_GITHUB_URL?: string
  readonly VITE_VIDEO_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
