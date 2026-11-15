import { describe, expect, it } from 'vitest'
import { shouldRetry } from '../app/queryClient'
import { mockApi, type MockResponse } from '../test/mockApi'
import { api, ApiError, isNotFound, readCookie, unwrap } from './client'

const LOGOUT = '/api/auth/logout'

/** 서버가 Set-Cookie로 토큰을 내려준 것처럼 쿠키를 둔다 (mock fetch는 Set-Cookie를 적용하지 않는다) */
function setCsrfCookie(value: string) {
  document.cookie = `XSRF-TOKEN=${value}; path=/`
}

function deleteCsrfCookie() {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
}

/** GET /api/ping 응답 때 쿠키를 심는다 */
function pingSetsCookie(value: string): () => MockResponse {
  return () => {
    setCsrfCookie(value)
    return { body: 'pong' }
  }
}

const logout = () => api.POST(LOGOUT)

describe('CSRF 헤더 미들웨어', () => {
  it('POST에는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 붙인다', async () => {
    setCsrfCookie('token-1')
    const server = mockApi({ [`POST ${LOGOUT}`]: { status: 204 } })

    await logout()

    const [call] = server.callsTo('POST', LOGOUT)
    expect(call.headers.get('X-XSRF-TOKEN')).toBe('token-1')
  })

  it('GET에는 붙이지 않는다', async () => {
    setCsrfCookie('token-1')
    const server = mockApi({ '/api/regions': { body: [] } })

    await api.GET('/api/regions')

    const [call] = server.callsTo('GET', '/api/regions')
    expect(call.headers.has('X-XSRF-TOKEN')).toBe(false)
  })

  it('토큰을 저장해 두지 않고 요청마다 쿠키에서 읽는다 (로그인 응답이 토큰을 바꾸는 경우)', async () => {
    setCsrfCookie('before-login')
    const server = mockApi({
      'POST /api/auth/login': () => {
        setCsrfCookie('after-login')
        return { body: { id: 1, email: 'a@example.com', role: 'USER' } }
      },
      [`POST ${LOGOUT}`]: { status: 204 },
    })

    await api.POST('/api/auth/login', { body: { email: 'a@example.com', password: 'password123' } })
    await logout()

    expect(server.callsTo('POST', '/api/auth/login')[0].headers.get('X-XSRF-TOKEN')).toBe('before-login')
    expect(server.callsTo('POST', LOGOUT)[0].headers.get('X-XSRF-TOKEN')).toBe('after-login')
  })

  it('쿠키가 없으면 GET /api/ping으로 먼저 받아 온 뒤 붙인다', async () => {
    const server = mockApi({ '/api/ping': pingSetsCookie('from-ping'), [`POST ${LOGOUT}`]: { status: 204 } })

    await logout()

    expect(server.calls.map((call) => `${call.method} ${call.url.pathname}`)).toEqual(['GET /api/ping', `POST ${LOGOUT}`])
    expect(server.callsTo('POST', LOGOUT)[0].headers.get('X-XSRF-TOKEN')).toBe('from-ping')
  })

  it('동시에 POST 두 개를 보내도 ping은 한 번만 보낸다', async () => {
    const server = mockApi({ '/api/ping': pingSetsCookie('from-ping'), [`POST ${LOGOUT}`]: { status: 204 } })

    await Promise.all([logout(), logout()])

    expect(server.to('/api/ping')).toHaveLength(1)
    expect(server.callsTo('POST', LOGOUT).map((call) => call.headers.get('X-XSRF-TOKEN'))).toEqual([
      'from-ping',
      'from-ping',
    ])
  })

  it('쿠키가 지워진 뒤(로그아웃)의 POST는 ping을 다시 한 번 보낸다', async () => {
    let issued = 0
    const server = mockApi({
      '/api/ping': () => pingSetsCookie(`token-${++issued}`)(),
      [`POST ${LOGOUT}`]: { status: 204 },
    })

    await logout()
    deleteCsrfCookie() // 서버의 로그아웃 응답이 쿠키를 지운 상황
    await logout()

    expect(server.to('/api/ping')).toHaveLength(2)
    expect(server.callsTo('POST', LOGOUT).map((call) => call.headers.get('X-XSRF-TOKEN'))).toEqual([
      'token-1',
      'token-2',
    ])
  })

  it('ping이 네트워크 오류로 실패해도 POST는 헤더 없이 나가고, 다음 POST에서 ping을 다시 시도한다', async () => {
    let pingFails = true
    const server = mockApi({
      '/api/ping': () => {
        if (pingFails) throw new TypeError('Failed to fetch')
        return pingSetsCookie('retried')()
      },
      [`POST ${LOGOUT}`]: { status: 204 },
    })

    await logout()
    pingFails = false
    await logout()

    expect(server.to('/api/ping')).toHaveLength(2)
    expect(server.callsTo('POST', LOGOUT).map((call) => call.headers.get('X-XSRF-TOKEN'))).toEqual([null, 'retried'])
  })

  it('ping 뒤에도 쿠키가 없으면 헤더 없이 보낸다 (서버가 403으로 거절)', async () => {
    const server = mockApi({ '/api/ping': { body: 'pong' }, [`POST ${LOGOUT}`]: { status: 403 } })

    const { response } = await logout()

    expect(response.status).toBe(403)
    expect(server.callsTo('POST', LOGOUT)[0].headers.has('X-XSRF-TOKEN')).toBe(false)
  })
})

describe('readCookie', () => {
  it('이름이 정확히 같은 쿠키 값을 읽고, URL 인코딩을 풀어 준다', () => {
    document.cookie = 'MY-XSRF-TOKEN=other; path=/'
    document.cookie = 'XSRF-TOKEN=a%2Bb%3D; path=/'
    document.cookie = 'SESSION=s; path=/'

    expect(readCookie('XSRF-TOKEN')).toBe('a+b=')
    expect(readCookie('SESSION')).toBe('s')
    expect(readCookie('TOKEN')).toBeUndefined()
  })
})

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
