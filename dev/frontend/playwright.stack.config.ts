import { defineConfig, devices } from '@playwright/test'

/**
 * `npm run e2e:stack`, local only: real logins against the running stack (Keycloak on 8180, core-app with
 * `local,demo`, the AML service with `local`) through the Vite dev server on 5173.
 * Needs TRUSTLEDGER_DEV_USER_PASSWORD (from dev/.env) in the environment.
 */
export default defineConfig({
  testDir: './e2e',
  grep: /@stack/,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
