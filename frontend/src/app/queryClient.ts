import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'

/** 4xx는 다시 요청해도 결과가 같으므로 재시도하지 않는다. 5xx·네트워크 오류만 1회 재시도. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status < 500) return false
  return failureCount < 1
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetry,
        staleTime: 5 * 60 * 1000,
        refetchOnWindowFocus: false,
      },
    },
  })
}
