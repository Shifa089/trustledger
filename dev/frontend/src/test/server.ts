import { http, HttpResponse } from 'msw/http'
import { setupServer } from 'msw/node'

/** The base URLs that vite.config.ts gives the tests (test.env). */
export const CORE = 'http://core.test/api/v1'
export const AML = 'http://aml.test/api/v1'

/** Default happy-path answers; a test overrides one with server.use(...). */
export const handlers = [
  http.get(`${CORE}/me`, () =>
    HttpResponse.json({
      id: '00000000-0000-0000-0000-000000000001',
      email: 'c*******1@example.test',
      phone: 'XXXXXX1234',
      fullName: 'Customer One',
      status: 'ACTIVE',
      roles: ['CUSTOMER'],
    }),
  ),
  http.get(`${AML}/aml/cases`, () =>
    HttpResponse.json({ content: [], page: 0, size: 1, totalElements: 3, totalPages: 3 }),
  ),
]

export const server = setupServer(...handlers)
