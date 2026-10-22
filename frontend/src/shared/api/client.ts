// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The one HTTP client of the interface: same origin, cookies only (the session cookie is
// HttpOnly and never seen here), and the CSRF token echoed on every state-changing call
// (ADR-004, ADR-022). Errors are RFC 9457 problems; only their stable `code` is used for
// messages (ADR-011). Each feature declares its endpoints in its own `api.ts` with `call`.

/** A refused call: HTTP status, the problem's stable code, and the problem body. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly body: Record<string, unknown>

  constructor(status: number, body: Record<string, unknown>) {
    const code = typeof body.code === 'string' ? body.code : `HTTP_${status}`
    super(code)
    this.status = status
    this.code = code
    this.body = body
  }
}

const API = '/api/v1'
let csrf: { cookieName: string; headerName: string } | null = null

function readCookie(name: string): string | null {
  for (const part of document.cookie.split(';')) {
    const [key, ...value] = part.trim().split('=')
    if (key === name) return decodeURIComponent(value.join('='))
  }
  return null
}

/** The current CSRF token, fetching one when the cookie is absent (it changes at login and logout). */
async function csrfHeader(): Promise<[string, string]> {
  if (!csrf || !readCookie(csrf.cookieName)) {
    const response = await fetch(`${API}/auth/csrf`, { credentials: 'same-origin' })
    if (!response.ok) throw new ApiError(response.status, {})
    csrf = await response.json()
  }
  const token = csrf && readCookie(csrf.cookieName)
  if (!csrf || !token) throw new ApiError(403, { code: 'CSRF_INVALID' })
  return [csrf.headerName, token]
}

/** Calls `/api/v1{path}`; throws {@link ApiError} for any status outside 2xx. */
export async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (method !== 'GET') {
    const [name, token] = await csrfHeader()
    headers[name] = token
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${API}${path}`, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const parsed = text ? JSON.parse(text) : {}
  if (!response.ok) throw new ApiError(response.status, parsed)
  return parsed as T
}

/** A query string from the defined, non-empty parameters. */
export function query(params: Record<string, string | number | undefined>): string {
  return new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => [k, String(v)])).toString()
}
