import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api, ApiError, unwrap, type ProblemDetail, type Schemas } from './client'
import { meQuery } from './queries'

/**
 * 로그인·로그아웃 뒤 캐시를 새 계정 상태로 바꾼다. me = 새 내 정보, 로그아웃이면 null.
 * 1. 늦게 도착할 /api/me 응답이 결과를 덮어쓰지 않게 진행 중인 요청을 먼저 취소
 * 2. 헤더가 보고 있는 ['me'] 칸은 지우지 않고 값만 바꾼다 (지웠다 새로 만들면 화면이 옛 칸을 붙잡는다)
 * 3. 이전 계정의 회원 데이터(['me', 'watchlist'] 등)만 지운다 (['me'] 자신은 길이 1이라 빠짐)
 */
async function replaceMe(queryClient: QueryClient, me: Schemas['MeResponse'] | null) {
  await queryClient.cancelQueries({ queryKey: meQuery.queryKey })
  queryClient.setQueryData(meQuery.queryKey, me)
  queryClient.removeQueries({ queryKey: ['me'], predicate: (query) => query.queryKey.length > 1 })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const result = await api.POST('/api/auth/logout')
      if (!result.response.ok) {
        throw new ApiError(result.response.status, result.error as ProblemDetail | undefined)
      }
    },
    onSuccess: () => replaceMe(queryClient, null),
    // 실패하면 아무것도 안 바꾼다: 서버 세션이 살아 있는데 화면만 로그아웃이면 거짓
  })
}

/** 로그인. 응답이 곧 내 정보(MeResponse)라 /api/me를 다시 묻지 않고 캐시에 넣는다 */
export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: Schemas['LoginRequest']) => api.POST('/api/auth/login', { body }).then(unwrap),
    onSuccess: (me) => replaceMe(queryClient, me),
  })
}
