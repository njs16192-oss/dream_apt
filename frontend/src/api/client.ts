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

/**
 * 경로·파라미터·응답 타입은 src/api/schema.d.ts(npm run gen:api로 생성)에서 온다.
 * fetch는 호출할 때마다 globalThis.fetch를 읽어야 테스트에서 바꿔 끼울 수 있다.
 */
export const api = createClient<paths>({
  baseUrl: globalThis.location?.origin ?? '',
  fetch: (request) => globalThis.fetch(request),
})

/** openapi-fetch 결과에서 data를 꺼내고, 실패면 ApiError를 던진다. TanStack Query의 queryFn에서 쓴다. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (!result.response.ok || result.data === undefined) {
    throw new ApiError(result.response.status, result.error as ProblemDetail | undefined)
  }
  return result.data
}
