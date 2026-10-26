import { vi } from 'vitest'

export type MockResponse = { status?: number; body: unknown }
type Route = MockResponse | ((url: URL) => MockResponse)

/**
 * fetch를 경로(pathname)별 응답으로 바꿔 끼운다. 정해지지 않은 경로는 404.
 * 받은 요청 URL을 기록하므로 경로별 요청 횟수·파라미터를 확인할 수 있다.
 * 되돌리기는 setup.ts의 vi.unstubAllGlobals()가 한다.
 */
export function mockApi(routes: Record<string, Route>) {
  const requests: URL[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url)
      requests.push(url)
      const route = routes[url.pathname]
      const { status = 200, body } = route
        ? typeof route === 'function'
          ? route(url)
          : route
        : { status: 404, body: { status: 404, detail: `mock 없음: ${url.pathname}` } }
      return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
    }),
  )
  return {
    requests,
    /** 해당 경로로 간 요청만 */
    to: (pathname: string) => requests.filter((url) => url.pathname === pathname),
  }
}
