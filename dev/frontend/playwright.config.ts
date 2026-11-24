import { defineConfig, devices } from '@playwright/test'

/**
 * CI and `npm run e2e`: the built app under `vite preview` on 4173, no backend. Public pages and the redirect to
 * Keycloak. The real-login specs (tagged @stack) run with playwright.stack.config.ts instead.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  grepInvert: /@stack/,
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run build && npm run preview',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    // Fixed values so the CI build needs no .env; nothing is listening on these.
    env: {
      VITE_CORE_API_URL: 'http://localhost:8080',
      VITE_AML_API_URL: 'http://localhost:8081',
      VITE_OIDC_AUTHORITY: 'http://localhost:8180/realms/trustledger',
      VITE_OIDC_CLIENT_ID: 'trustledger-web',
      VITE_GITHUB_URL: 'https://github.com/example/trustledger',
    },
  },
})
