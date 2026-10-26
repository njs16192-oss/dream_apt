import { afterEach, describe, expect, it, vi } from 'vitest'
import { shouldRetry } from '../app/queryClient'
import { api, ApiError, isNotFound, unwrap } from './client'

function stubFetch(status: number, body: unknown) {
  const fetchMock = vi.fn(async (_request: Request) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('api + unwrap', () => {
  it('경로 파라미터를 채워 요청하고 성공 응답의 본문을 돌려준다', async () => {
    const fetchMock = stubFetch(200, [{ sggCd: '11710', sidoName: '서울특별시', sggName: '송파구' }])

    const regions = await api.GET('/api/regions').then(unwrap)

    expect(regions).toEqual([{ sggCd: '11710', sidoName: '서울특별시', sggName: '송파구' }])
    expect(new URL(fetchMock.mock.calls[0][0].url).pathname).toBe('/api/regions')
  })

  it('쿼리 파라미터를 붙인다', async () => {
    const fetchMock = stubFetch(200, [])

    await api.GET('/api/complexes', { params: { query: { q: '헬리오', sggCd: '11710' } } }).then(unwrap)

    const url = new URL(fetchMock.mock.calls[0][0].url)
    expect(url.pathname).toBe('/api/complexes')
    expect(url.searchParams.get('q')).toBe('헬리오')
    expect(url.searchParams.get('sggCd')).toBe('11710')
  })

  it('404는 ProblemDetail을 담은 ApiError가 된다', async () => {
    stubFetch(404, { title: 'Not Found', status: 404, detail: '단지를 찾을 수 없습니다: X' })

    const error = await api
      .GET('/api/complexes/{aptSeq}', { params: { path: { aptSeq: 'X' } } })
      .then(unwrap)
      .catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(isNotFound(error)).toBe(true)
    expect((error as ApiError).problem?.detail).toBe('단지를 찾을 수 없습니다: X')
  })

  it('500은 404가 아닌 ApiError가 된다', async () => {
    stubFetch(500, { title: 'Internal Server Error', status: 500 })

    const error = await api.GET('/api/regions').then(unwrap).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(500)
    expect(isNotFound(error)).toBe(false)
  })
})

describe('shouldRetry', () => {
  it('4xx는 재시도하지 않는다', () => {
    expect(shouldRetry(0, new ApiError(404))).toBe(false)
    expect(shouldRetry(0, new ApiError(400))).toBe(false)
  })

  it('5xx와 네트워크 오류는 한 번만 재시도한다', () => {
    expect(shouldRetry(0, new ApiError(503))).toBe(true)
    expect(shouldRetry(1, new ApiError(503))).toBe(false)
    expect(shouldRetry(0, new TypeError('Failed to fetch'))).toBe(true)
    expect(shouldRetry(1, new TypeError('Failed to fetch'))).toBe(false)
  })
})
