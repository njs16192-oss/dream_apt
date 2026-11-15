import { MutationCache, QueryCache, QueryClient, type QueryKey } from '@tanstack/react-query'
import { clearSession } from '../api/auth'
import { ApiError } from '../api/client'

/** 4xx는 다시 요청해도 결과가 같으므로 재시도하지 않는다. 5xx·네트워크 오류만 1회 재시도. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status < 500) return false
  return failureCount < 1
}

/**
 * 회원 데이터(['me', …] 키) 요청이 401이면 세션이 끝난 것이다 (만료, 다른 탭에서 로그아웃).
 * /api/me 자신은 401을 null로 돌려주고 던지지 않으므로 여기에 오지 않는다.
 * 공개 API의 401(예: 로그인 실패)은 키가 'me'가 아니라서 걸리지 않는다.
 */
function isSessionExpired(error: unknown, key: QueryKey | undefined): boolean {
  return error instanceof ApiError && error.status === 401 && key?.[0] === 'me'
}

export function createQueryClient(): QueryClient {
  // onError가 queryClient를 써야 해서, 만든 뒤에 채워 넣는다
  let queryClient: QueryClient | undefined
  const onSessionExpired = () => {
    if (queryClient) void clearSession(queryClient)
  }

  queryClient = new QueryClient({
    // useQuery마다 onError를 다는 대신 캐시 전체에서 한 번에 본다 (v5에는 useQuery의 onError가 없다)
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (isSessionExpired(error, query.queryKey)) onSessionExpired()
      },
    }),
    // mutation은 키가 있어야 판단할 수 있다. 회원 데이터를 바꾸는 mutation은 mutationKey를 ['me', …]로 단다
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (isSessionExpired(error, mutation.options.mutationKey)) onSessionExpired()
      },
    }),
    defaultOptions: {
      queries: {
        retry: shouldRetry,
        staleTime: 5 * 60 * 1000,
        refetchOnWindowFocus: false,
      },
    },
  })
  return queryClient
}
