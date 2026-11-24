import { ApiError } from '@/api/errors'
import { shouldRetry } from './queryClient'

describe('query retries', () => {
  it.each([400, 401, 403, 404, 409, 422, 429])('never retries a %s', (status) => {
    expect(shouldRetry(0, new ApiError(status, {}))).toBe(false)
  })

  it('retries network and server errors twice', () => {
    for (const error of [new ApiError(0, {}), new ApiError(503, {}), new Error('boom')]) {
      expect(shouldRetry(0, error)).toBe(true)
      expect(shouldRetry(1, error)).toBe(true)
      expect(shouldRetry(2, error)).toBe(false)
    }
  })
})
