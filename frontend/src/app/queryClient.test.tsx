import { QueryClientProvider, useMutation, type QueryClient } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { ME_KEY } from '../api/queries'
import { createQueryClient } from './queryClient'

const ME = { id: 1, email: 'user@example.com', role: 'USER' as const }

/** 로그인한 상태의 캐시: 내 정보 + 회원 데이터 + 공개 데이터 */
function loggedInCache(): QueryClient {
  const queryClient = createQueryClient()
  queryClient.setQueryData(ME_KEY, ME)
  queryClient.setQueryData(['me', 'notifications', 0], { content: [] })
  queryClient.setQueryData(['complexes', '11710-1'], { aptSeq: '11710-1' })
  return queryClient
}

/** key 쿼리를 status 오류로 실패시킨다 */
function failQuery(queryClient: QueryClient, key: readonly unknown[], status: number) {
  return queryClient
    .fetchQuery({
      queryKey: key,
      queryFn: () => Promise.reject(new ApiError(status)),
      retry: false,
    })
    .catch(() => undefined)
}

/** 세션이 끝난 상태: 내 정보 칸은 남아 있고 값은 null, 회원 데이터는 없음, 공개 데이터는 그대로 */
function expectLoggedOut(queryClient: QueryClient) {
  expect(queryClient.getQueryCache().find({ queryKey: ME_KEY, exact: true })).toBeDefined()
  expect(queryClient.getQueryData(ME_KEY)).toBeNull()
  expect(queryClient.getQueryCache().findAll({ queryKey: ['me'] })).toHaveLength(1)
  expect(queryClient.getQueryData(['complexes', '11710-1'])).toEqual({ aptSeq: '11710-1' })
}

/** 시간을 조금 준 뒤에도 로그인 상태가 그대로인지 (처리가 비동기라 바로 보면 안 바뀐 것처럼 보인다) */
async function expectStillLoggedIn(queryClient: QueryClient) {
  await new Promise((resolve) => setTimeout(resolve, 20))
  expect(queryClient.getQueryData(ME_KEY)).toEqual(ME)
  expect(queryClient.getQueryData(['me', 'notifications', 0])).toEqual({ content: [] })
}

describe('회원 데이터 쿼리의 401 (세션 만료)', () => {
  it("['me', …] 쿼리가 401이면 내 정보를 null로 바꾸고 회원 데이터를 지운다", async () => {
    const queryClient = loggedInCache()

    await failQuery(queryClient, ['me', 'watchlist'], 401)

    await waitFor(() => expectLoggedOut(queryClient))
  })

  it.each([403, 404, 500])("['me', …] 쿼리가 %d이면 그대로 둔다", async (status) => {
    const queryClient = loggedInCache()

    await failQuery(queryClient, ['me', 'watchlist'], status)

    await expectStillLoggedIn(queryClient)
  })

  it('공개 데이터 쿼리의 401은 세션과 상관없다', async () => {
    const queryClient = loggedInCache()

    await failQuery(queryClient, ['complexes', 'X'], 401)

    await expectStillLoggedIn(queryClient)
  })

  it('ApiError가 아닌 오류는 그대로 둔다', async () => {
    const queryClient = loggedInCache()

    await queryClient
      .fetchQuery({ queryKey: ['me', 'watchlist'], queryFn: () => Promise.reject(new TypeError('Failed to fetch')), retry: false })
      .catch(() => undefined)

    await expectStillLoggedIn(queryClient)
  })

  it('여러 쿼리가 한꺼번에 401을 받아도 끝 상태는 같다', async () => {
    const queryClient = loggedInCache()

    await Promise.all([
      failQuery(queryClient, ['me', 'watchlist'], 401),
      failQuery(queryClient, ['me', 'unread-count'], 401),
      failQuery(queryClient, ['me', 'notifications', 1], 401),
    ])

    await waitFor(() => expectLoggedOut(queryClient))
  })
})

describe('회원 데이터 mutation의 401', () => {
  function renderMutation(queryClient: QueryClient, mutationKey: readonly unknown[] | undefined, status: number) {
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    return renderHook(
      () => useMutation({ mutationKey, mutationFn: () => Promise.reject(new ApiError(status)) }),
      { wrapper },
    )
  }

  it("mutationKey가 ['me', …]이고 401이면 로그아웃 상태로 바꾼다", async () => {
    const queryClient = loggedInCache()
    const { result } = renderMutation(queryClient, ['me', 'watchlist', 'add'], 401)

    act(() => result.current.mutate())

    await waitFor(() => expectLoggedOut(queryClient))
  })

  it('mutationKey가 없으면 401이어도 그대로 둔다 (로그인 실패 등)', async () => {
    const queryClient = loggedInCache()
    const { result } = renderMutation(queryClient, undefined, 401)

    act(() => result.current.mutate())
    await waitFor(() => expect(result.current.isError).toBe(true))

    await expectStillLoggedIn(queryClient)
  })

  it("mutationKey가 ['me', …]여도 409는 그대로 둔다", async () => {
    const queryClient = loggedInCache()
    const { result } = renderMutation(queryClient, ['me', 'watchlist', 'add'], 409)

    act(() => result.current.mutate())
    await waitFor(() => expect(result.current.isError).toBe(true))

    await expectStillLoggedIn(queryClient)
  })
})
