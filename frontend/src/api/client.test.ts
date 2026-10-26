import { describe, expect, it } from 'vitest'
import { shouldRetry } from '../app/queryClient'
import { mockApi } from '../test/mockApi'
import { api, ApiError, isNotFound, unwrap } from './client'

describe('api + unwrap', () => {
  it('성공 응답의 본문을 돌려준다', async () => {
    const regions = [{ sggCd: '11710', sidoName: '서울특별시', sggName: '송파구' }]
    const server = mockApi({ '/api/regions': { body: regions } })

    expect(await api.GET('/api/regions').then(unwrap)).toEqual(regions)
    expect(server.to('/api/regions')).toHaveLength(1)
  })

  it('경로·쿼리 파라미터를 채워 요청한다', async () => {
    const server = mockApi({ '/api/complexes': { body: [] }, '/api/complexes/11710-1': { body: {} } })

    await api.GET('/api/complexes', { params: { query: { q: '헬리오', sggCd: '11710' } } }).then(unwrap)
    await api.GET('/api/complexes/{aptSeq}', { params: { path: { aptSeq: '11710-1' } } }).then(unwrap)

    const [search] = server.to('/api/complexes')
    expect(search.searchParams.get('q')).toBe('헬리오')
    expect(search.searchParams.get('sggCd')).toBe('11710')
    expect(server.to('/api/complexes/11710-1')).toHaveLength(1)
  })

  it('404는 ProblemDetail을 담은 ApiError가 된다', async () => {
    mockApi({ '/api/complexes/X': { status: 404, body: { title: 'Not Found', status: 404, detail: '단지를 찾을 수 없습니다: X' } } })

    const error = await api
      .GET('/api/complexes/{aptSeq}', { params: { path: { aptSeq: 'X' } } })
      .then(unwrap)
      .catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(isNotFound(error)).toBe(true)
    expect((error as ApiError).problem?.detail).toBe('단지를 찾을 수 없습니다: X')
  })

  it('500은 404가 아닌 ApiError가 된다', async () => {
    mockApi({ '/api/regions': { status: 500, body: { title: 'Internal Server Error', status: 500 } } })

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
