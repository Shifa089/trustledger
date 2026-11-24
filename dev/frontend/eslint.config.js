import js from '@eslint/js'
import { defineConfig, globalIgnores } from 'eslint/config'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import globals from 'globals'
import tseslint from 'typescript-eslint'

// The frontend rules from CLAUDE.md, enforced where a linter can:
// - all API calls go through the API client: fetch only inside src/api/
// - tokens never in localStorage/sessionStorage: both globals banned (src/auth/oidc.ts has the one
//   documented exception, the PKCE state store, which never holds a token)
// - no money arithmetic with floating point: parseFloat banned; use src/lib/money.ts
const restrictedGlobals = [
  { name: 'fetch', message: 'Call the backend through the API client (src/api), never fetch directly.' },
  { name: 'localStorage', message: 'Tokens and personal data never go to localStorage.' },
  { name: 'sessionStorage', message: 'Tokens and personal data never go to sessionStorage.' },
  { name: 'parseFloat', message: 'Money is never a float: use parseRupees/formatRupees from src/lib/money.ts.' },
]

export default defineConfig([
  globalIgnores(['dist', 'coverage', 'playwright-report', 'test-results', 'node_modules']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      'no-restricted-globals': ['error', ...restrictedGlobals],
      'no-restricted-properties': [
        'error',
        { object: 'window', property: 'fetch', message: 'Use the API client (src/api).' },
        { object: 'window', property: 'localStorage', message: 'Tokens never in localStorage.' },
        { object: 'window', property: 'sessionStorage', message: 'Tokens never in sessionStorage.' },
        { object: 'Number', property: 'parseFloat', message: 'Money is never a float: use src/lib/money.ts.' },
      ],
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
    },
  },
  {
    // The API client is the one place that talks HTTP.
    files: ['src/api/client.ts'],
    rules: {
      'no-restricted-globals': ['error', ...restrictedGlobals.filter((g) => g.name !== 'fetch')],
    },
  },
  {
    // Tests may read storage to prove tokens are not there; shadcn files export variants next to components.
    files: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'e2e/**', 'src/components/ui/**'],
    rules: {
      'no-restricted-globals': 'off',
      'react-refresh/only-export-components': 'off',
    },
  },
  {
    files: ['*.config.{ts,js}', 'e2e/**/*.ts'],
    languageOptions: { globals: globals.node },
  },
])
