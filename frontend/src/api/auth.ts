import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, type ProblemDetail } from './client'
import { meQuery } from './queries'   // ME_KEY 대신

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const result = await api.POST('/api/auth/logout')
      if (!result.response.ok) {
        throw new ApiError(result.response.status, result.error as ProblemDetail | undefined)
      }
    },
    onSuccess: async () => {
      // 늦게 도착할 /api/me 응답이 로그아웃 결과를 덮어쓰지 않게 먼저 취소
      await queryClient.cancelQueries({ queryKey: meQuery.queryKey })
      queryClient.setQueryData(meQuery.queryKey, null)
      queryClient.removeQueries({ queryKey: ['me'], predicate: (query) => query.queryKey.length > 1 })
    },
    // 실패하면 아무것도 안 바꾼다: 서버 세션이 살아 있는데 화면만 로그아웃이면 거짓
  })
}