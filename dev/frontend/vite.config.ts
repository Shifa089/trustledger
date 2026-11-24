/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Dev server on 5173 and preview on 4173: both are in the Keycloak client trustledger-web and in the
// backend CORS allowlists (local profile). strictPort, so a busy port fails instead of silently moving.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: { port: 5173, strictPort: true },
  preview: { port: 4173, strictPort: true },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    // Fixed values so tests never depend on a developer's .env.local.
    env: {
      VITE_CORE_API_URL: 'http://core.test',
      VITE_AML_API_URL: 'http://aml.test',
      VITE_OIDC_AUTHORITY: 'http://keycloak.test/realms/trustledger',
      VITE_OIDC_CLIENT_ID: 'trustledger-web',
      VITE_GITHUB_URL: 'https://github.com/example/trustledger',
      VITE_VIDEO_URL: '',
    },
  },
})
