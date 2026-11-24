import { currentAccessToken } from '@/auth/oidc'
import { config } from '@/config'
import { createApiClient } from './client'

/** core-app (port 8080 locally): identity, KYC, wallet, payments, ledger, audit, privacy. */
export const coreApi = createApiClient({ baseUrl: config.coreApiUrl, getAccessToken: currentAccessToken })

/** risk-aml-service (port 8081 locally): AML alerts and cases. */
export const amlApi = createApiClient({ baseUrl: config.amlApiUrl, getAccessToken: currentAccessToken })

export { ApiError, isApiError, friendlyMessage } from './errors'
export type { ApiClient, RequestOptions } from './client'
