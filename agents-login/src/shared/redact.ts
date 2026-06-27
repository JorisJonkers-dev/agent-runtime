// Redaction helpers shared by controller and worker. The portal handles raw
// OAuth credentials, so any value that looks like a token, key, or credential
// blob must never reach logs or HTTP responses verbatim.

const SECRET_KEY_PATTERN =
  /(access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|api[_-]?key|secret|password|authorization|bearer|credential|\.credentials|auth\.json|private[_-]?key)/i

// Long opaque strings that look like tokens/keys even without a labelled key.
const OPAQUE_TOKEN_PATTERN = /\b(?:sk-[A-Za-z0-9_-]{12,}|[A-Za-z0-9_-]{40,})\b/g

// JWT-shaped values (three base64url segments separated by dots).
const JWT_PATTERN = /\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b/g

export const REDACTED = '«redacted»'

/** Redact secret-looking substrings from a freeform string (e.g. PTY output). */
export function redactString(input: string): string {
  if (typeof input !== 'string' || input.length === 0) {
    return input
  }
  return input.replace(JWT_PATTERN, REDACTED).replace(OPAQUE_TOKEN_PATTERN, REDACTED)
}

/**
 * Deep-redact an arbitrary value for logging / responses. Keys whose name looks
 * secret-bearing are fully masked; string values are scrubbed of token-shaped
 * substrings.
 */
export function redactValue(value: unknown, keyHint = ''): unknown {
  if (value === null || value === undefined) {
    return value
  }
  if (typeof value === 'string') {
    if (SECRET_KEY_PATTERN.test(keyHint)) {
      return REDACTED
    }
    return redactString(value)
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return value
  }
  if (Array.isArray(value)) {
    return value.map((v) => redactValue(v, keyHint))
  }
  if (typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (SECRET_KEY_PATTERN.test(k)) {
        out[k] = REDACTED
      } else {
        out[k] = redactValue(v, k)
      }
    }
    return out
  }
  return value
}
