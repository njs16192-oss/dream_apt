import { QueryClientProvider, useQuery, type QueryClient } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { mockApi } from '../test/mockApi'
import { useLogin, useLogout } from './auth'
import { ApiError } from './client'
import { ME_KEY, meQuery } from './queries'

const ME = { id: 1, email: 'user@example.com', role: 'USER' as const }

/** 실제와 같은 설정(재시도 정책 포함)에 재시도 간격만 0 */
function testQueryClient(): QueryClient {
  const queryClient = createQueryClient()
  queryClient.setDefaultOptions({ queries: { ...queryClient.getDefaultOptions().queries, retryDelay: 0 } })
  return queryClient
}

function withClient(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}

describe('meQuery', () => {
  it('로그인했으면 회원 정보', async () => {
    mockApi({ 'GET /api/me': { body: ME } })

    expect(await testQueryClient().fetchQuery(meQuery)).toEqual(ME)
  })

  it('401은 오류가 아니라 null(비로그인)이고, 다시 묻지 않는다', async () => {
    const server = mockApi({}) // mockApi 기본값이 /api/me 401
    const queryClient = testQueryClient()

    expect(await queryClient.fetchQuery(meQuery)).toBeNull()
    expect(queryClient.getQueryState(ME_KEY)?.status).toBe('success')
    expect(server.to('/api/me')).toHaveLength(1)
  })

  it('화면에서 쓸 때 401은 재시도 없이 null, 500은 한 번 재시도한 뒤 오류', async () => {
    const unauthorized = mockApi({})
    const first = renderHook(() => useQuery(meQuery), { wrapper: withClient(testQueryClient()) })
    await waitFor(() => expect(first.result.current.isSuccess).toBe(true))
    expect(first.result.current.data).toBeNull()
    expect(unauthorized.to('/api/me')).toHaveLength(1)

    const failing = mockApi({ 'GET /api/me': { status: 500, body: { status: 500 } } })
    const second = renderHook(() => useQuery(meQuery), { wrapper: withClient(testQueryClient()) })
    await waitFor(() => expect(second.result.current.isError).toBe(true))
    expect((second.result.current.error as ApiError).status).toBe(500)
    expect(failing.to('/api/me')).toHaveLength(2)
  })

  it('staleTime이 무한이라 같은 키를 다시 써도 요청하지 않는다', async () => {
    const server = mockApi({ 'GET /api/me': { body: ME } })
    const queryClient = testQueryClient()

    await queryClient.fetchQuery(meQuery)
    // 같은 밀리초 안이면 staleTime 0이어도 신선하다고 본다. 조금 지난 뒤 다시 쓴다
    await new Promise((resolve) => setTimeout(resolve, 20))
    await queryClient.fetchQuery(meQuery)

    expect(server.to('/api/me')).toHaveLength(1)
  })
})

describe('useLogin', () => {
  it('성공하면 응답을 내 정보로 넣고, 이전 계정의 회원 데이터만 지운다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const other = { id: 2, email: 'other@example.com', role: 'USER' as const }
    const server = mockApi({ 'POST /api/auth/login': { body: other } })
    const queryClient = testQueryClient()
    queryClient.setQueryData(ME_KEY, null)
    queryClient.setQueryData(['me', 'watchlist'], [{ aptSeq: '11710-1' }]) // 이전 계정이 남긴 것
    queryClient.setQueryData(['complexes', '11710-1'], { aptSeq: '11710-1' })
    const { result } = renderHook(() => useLogin(), { wrapper: withClient(queryClient) })

    act(() => result.current.mutate({ email: 'other@example.com', password: 'password123' }))
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(queryClient.getQueryData(ME_KEY)).toEqual(other)
    expect(queryClient.getQueryData(['me', 'watchlist'])).toBeUndefined()
    expect(queryClient.getQueryData(['complexes', '11710-1'])).toEqual({ aptSeq: '11710-1' })
    expect(server.to('/api/me')).toHaveLength(0)
  })

  it('실패하면 ApiError이고 캐시는 그대로', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    mockApi({ 'POST /api/auth/login': { status: 401, body: { status: 401 } } })
    const queryClient = testQueryClient()
    queryClient.setQueryData(ME_KEY, null)
    const { result } = renderHook(() => useLogin(), { wrapper: withClient(queryClient) })

    act(() => result.current.mutate({ email: 'a@example.com', password: 'x' }))
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect((result.current.error as ApiError).status).toBe(401)
    expect(queryClient.getQueryData(ME_KEY)).toBeNull()
  })
})

describe('useLogout', () => {
  /** 로그인한 상태의 캐시: 내 정보 + 회원 데이터 + 공개 데이터 */
  function loggedInCache(): QueryClient {
    const queryClient = testQueryClient()
    queryClient.setQueryData(ME_KEY, ME)
    queryClient.setQueryData(['me', 'watchlist'], [{ aptSeq: '11710-1' }])
    queryClient.setQueryData(['me', 'notifications', 0], { content: [] })
    queryClient.setQueryData(['complexes', '11710-1'], { aptSeq: '11710-1' })
    return queryClient
  }

  function renderLogout(queryClient: QueryClient) {
    return renderHook(() => useLogout(), { wrapper: withClient(queryClient) })
  }

  it('성공하면 내 정보는 null로 바꾸고 회원 데이터만 지운다. 공개 데이터는 남긴다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const server = mockApi({ 'POST /api/auth/logout': { status: 204 } })
    const queryClient = loggedInCache()
    const { result } = renderLogout(queryClient)

    act(() => result.current.mutate())
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(server.callsTo('POST', '/api/auth/logout')).toHaveLength(1)
    // 본체 칸은 남아 있고 값만 null (지우면 화면이 옛 칸을 붙잡는다)
    expect(queryClient.getQueryCache().find({ queryKey: ME_KEY, exact: true })).toBeDefined()
    expect(queryClient.getQueryData(ME_KEY)).toBeNull()
    expect(queryClient.getQueryCache().findAll({ queryKey: ['me'] })).toHaveLength(1)
    expect(queryClient.getQueryData(['complexes', '11710-1'])).toEqual({ aptSeq: '11710-1' })
  })

  it('실패하면 캐시를 그대로 둔다 (서버 세션이 살아 있을 수 있다)', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    mockApi({ 'POST /api/auth/logout': { status: 500, body: { status: 500 } } })
    const queryClient = loggedInCache()
    const { result } = renderLogout(queryClient)

    act(() => result.current.mutate())
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.error).toBeInstanceOf(ApiError)
    expect(queryClient.getQueryData(ME_KEY)).toEqual(ME)
    expect(queryClient.getQueryData(['me', 'watchlist'])).toEqual([{ aptSeq: '11710-1' }])
  })
})
