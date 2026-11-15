import createClient from 'openapi-fetch'
import type { components, paths } from './schema'

export type Schemas = components['schemas']

/** 백엔드 오류 응답 본문 (Spring ProblemDetail, RFC 9457). 조회 API는 이 형식으로 오류를 준다. */
export type ProblemDetail = {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
}

/** HTTP 상태가 2xx가 아닌 응답. 네트워크 오류는 fetch가 던지는 TypeError 그대로 올라간다. */
export class ApiError extends Error {
  readonly status: number
  readonly problem?: ProblemDetail

  constructor(status: number, problem?: ProblemDetail) {
    super(problem?.detail ?? `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

const baseUrl = globalThis.location?.origin ?? ''

/**
 * 경로·파라미터·응답 타입은 src/api/schema.d.ts(npm run gen:api로 생성)에서 온다.
 * fetch는 호출할 때마다 globalThis.fetch를 읽어야 테스트에서 바꿔 끼울 수 있다.
 */
export const api = createClient<paths>({
  baseUrl,
  fetch: (request) => globalThis.fetch(request),
})

/** 서버(csrf.spa())가 내려주는 CSRF 토큰 쿠키와, 같은 값을 되돌려 보낼 헤더 (ADR 011 ①) */
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'

/** 서버 상태를 바꾸지 않는 메서드. CSRF 검사 대상이 아니다 */
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])

/** document.cookie에서 이름이 정확히 같은 쿠키 값을 읽는다. 없으면 undefined */
export function readCookie(name: string): string | undefined {
  for (const pair of document.cookie.split(';')) {
    const index = pair.indexOf('=')
    if (index < 0) continue
    if (pair.slice(0, index).trim() === name) {
      return decodeURIComponent(pair.slice(index + 1).trim())
    }
  }
  return undefined
}

let pendingCsrfCookie: Promise<void> | null = null

/**
 * CSRF 쿠키가 없을 때 GET /api/ping을 보내 받아 온다 (서버는 아무 응답에나 쿠키를 싣는다).
 * 동시에 여러 번 불리면 진행 중인 요청 하나를 같이 기다린다. 끝나면 비워서, 로그아웃으로 쿠키가 지워진 뒤에는 다시 보낸다.
 * 미들웨어를 다시 타지 않게 api가 아니라 fetch를 직접 쓴다.
 */
function ensureCsrfCookie(): Promise<void> {
  pendingCsrfCookie ??= globalThis
    .fetch(new Request(`${baseUrl}/api/ping`, { credentials: 'same-origin' }))
    .then(
      () => undefined,
      // 받지 못하면 헤더 없이 보내고 서버의 403을 그대로 받는다
      () => undefined,
    )
    .finally(() => {
      pendingCsrfCookie = null
    })
  return pendingCsrfCookie
}

/**
 * GET 이외 요청에 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 붙인다.
 * 토큰은 저장해 두지 않고 요청마다 쿠키에서 읽는다. 로그인 응답이 쿠키를 새 토큰으로 바꾸면 다음 요청부터 새 값이 나간다.
 */
api.use({
  async onRequest({ request }) {
    if (SAFE_METHODS.has(request.method)) return undefined
    let token = readCookie(CSRF_COOKIE)
    if (!token) {
      await ensureCsrfCookie()
      token = readCookie(CSRF_COOKIE)
    }
    if (token) request.headers.set(CSRF_HEADER, token)
    return request
  },
})

/** openapi-fetch 결과에서 data를 꺼내고, 실패면 ApiError를 던진다. TanStack Query의 queryFn에서 쓴다. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (!result.response.ok || result.data === undefined) {
    throw new ApiError(result.response.status, result.error as ProblemDetail | undefined)
  }
  return result.data
}
