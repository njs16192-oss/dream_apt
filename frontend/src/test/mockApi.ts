import { vi } from 'vitest'

export type MockResponse = { status?: number; body?: unknown }
type Route = MockResponse | ((url: URL, request: Request) => MockResponse | Promise<MockResponse>)

/** 받은 요청 한 건. body는 텍스트 그대로 (JSON이면 JSON.parse로 확인) */
export type RecordedRequest = {
  url: URL
  method: string
  headers: Headers
  body: string
}

/**
 * 따로 정하지 않으면 쓰는 응답. 헤더(Layout)가 모든 화면에서 /api/me를 부르므로, 기본은 실제와 같은 "비로그인"(401)이다.
 * 없으면 404가 되어 실제로는 없는 상황으로 그려진다.
 */
const DEFAULT_ROUTES: Record<string, Route> = {
  'GET /api/me': { status: 401, body: { title: 'Unauthorized', status: 401, detail: '로그인이 필요합니다' } },
  // 로그인 상태로 그리면 헤더가 안 읽은 알림 수를 묻는다. 정하지 않으면 0개
  'GET /api/me/notifications/unread-count': { body: { count: 0 } },
}

/**
 * fetch를 경로별 응답으로 바꿔 끼운다. 정해지지 않은 경로는 404.
 * - 키는 pathname(모든 메서드) 또는 "POST /api/auth/login"처럼 메서드를 붙인 형태. 메서드를 붙인 쪽이 먼저다
 * - 함수가 Promise를 돌려주면 그동안 응답을 미룬다 (로딩 중 화면 확인용)
 * - 받은 요청을 기록하므로 경로별 요청 횟수·파라미터·헤더·본문을 확인할 수 있다
 * 되돌리기는 setup.ts의 vi.unstubAllGlobals()가 한다.
 */
export function mockApi(routes: Record<string, Route>) {
  const requests: URL[] = []
  const calls: RecordedRequest[] = []
  const merged = { ...DEFAULT_ROUTES, ...routes }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url)
      requests.push(url)
      calls.push({ url, method: request.method, headers: new Headers(request.headers), body: await request.clone().text() })
      const route = merged[`${request.method} ${url.pathname}`] ?? merged[url.pathname]
      const { status = 200, body } = route
        ? typeof route === 'function'
          ? await route(url, request)
          : route
        : { status: 404, body: { status: 404, detail: `mock 없음: ${request.method} ${url.pathname}` } }
      // 204 등 본문 없는 상태에는 본문을 실을 수 없다 (Response 생성자가 던진다)
      const hasBody = body !== undefined && status !== 204
      return new Response(hasBody ? JSON.stringify(body) : null, {
        status,
        headers: hasBody ? { 'Content-Type': 'application/json' } : undefined,
      })
    }),
  )
  return {
    requests,
    calls,
    /** 해당 경로로 간 요청만 */
    to: (pathname: string) => requests.filter((url) => url.pathname === pathname),
    /** 해당 메서드·경로로 간 요청만 (헤더·본문 포함) */
    callsTo: (method: string, pathname: string) =>
      calls.filter((call) => call.method === method && call.url.pathname === pathname),
  }
}

