import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, type ProblemDetail } from './client'
import { ME_KEY } from './queries'

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const result = await api.POST('/api/auth/logout')
      if (!result.response.ok) {
        throw new ApiError(result.response.status, result.error as ProblemDetail | undefined)
      }
    },
    onSuccess: () => {
      // 헤더가 보고 있는 ['me'] 칸은 지우지 않고 값만 비로그인으로
      queryClient.setQueryData(ME_KEY, null)
      // ['me', 'watchlist'] 같은 회원 데이터만 지운다 (['me'] 자신은 길이 1이라 빠짐)
      queryClient.removeQueries({ queryKey: ['me'], predicate: (query) => query.queryKey.length > 1 })
    },
    // 실패하면 아무것도 안 바꾼다: 서버 세션이 살아 있는데 화면만 로그아웃이면 거짓
  })
}