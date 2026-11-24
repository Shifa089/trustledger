import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { FRIENDLY_MESSAGES, friendlyMessage, toProblemDetail } from './errors'

/** The errorCode constants of a backend ErrorCode enum, read from its source. */
function enumCodes(relativePath: string): string[] {
  const source = readFileSync(resolve(import.meta.dirname, '../../..', relativePath), 'utf8')
  return [...source.matchAll(/^\s{4}([A-Z][A-Z_]+)\(/gm)].map((match) => match[1] ?? '')
}

describe('error messages', () => {
  it.each([
    'core-app/src/main/java/com/trustledger/core/shared/ErrorCode.java',
    'risk-aml-service/src/main/java/com/trustledger/aml/shared/ErrorCode.java',
  ])('has a friendly message for every errorCode in %s', (path) => {
    const codes = enumCodes(path)
    expect(codes.length).toBeGreaterThan(5)
    expect(codes.filter((code) => !(code in FRIENDLY_MESSAGES))).toEqual([])
  })

  it('never shows an empty or raw-code message', () => {
    for (const [code, message] of Object.entries(FRIENDLY_MESSAGES)) {
      expect(message.length).toBeGreaterThan(10)
      expect(message).not.toContain(code)
    }
  })

  it('uses the generic message for an unknown or missing code', () => {
    expect(friendlyMessage('NOPE')).toBe(FRIENDLY_MESSAGES.UNKNOWN_ERROR)
    expect(friendlyMessage(undefined)).toBe(FRIENDLY_MESSAGES.UNKNOWN_ERROR)
    expect(friendlyMessage('toString')).toBe(FRIENDLY_MESSAGES.UNKNOWN_ERROR)
  })

  it('parses only well-typed ProblemDetail fields', () => {
    expect(toProblemDetail(null)).toEqual({})
    expect(toProblemDetail('oops')).toEqual({})
    expect(toProblemDetail({ errorCode: 42, title: 'T', status: '400', violations: [{ field: 'a' }, 'x'] })).toEqual({
      title: 'T',
      violations: [{ field: 'a', message: '' }],
    })
  })
})
