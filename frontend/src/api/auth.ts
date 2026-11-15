import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api, ensureOk, unwrap, type Schemas } from './client'
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

/**
 * 세션이 끝났을 때(회원 API의 401) 로그아웃 상태로 바꾼다. createQueryClient의 onError가 부른다.
 * 여러 요청이 한꺼번에 401을 받을 수 있어서, 이미 비로그인(null)이면 아무것도 하지 않는다.
 */
export async function clearSession(queryClient: QueryClient) {
  if (queryClient.getQueryData(meQuery.queryKey) === null) return
  await replaceMe(queryClient, null)
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => ensureOk(await api.POST('/api/auth/logout')),
    onSuccess: () => replaceMe(queryClient, null),
    // 실패하면 아무것도 안 바꾼다: 서버 세션이 살아 있는데 화면만 로그아웃이면 거짓
  })
}

/** 가입. 서버는 가입만 하고 로그인하지 않으므로 캐시는 그대로 둔다 (화면이 로그인 화면으로 보낸다) */
export function useSignup() {
  return useMutation({
    mutationFn: (body: Schemas['SignupRequest']) => api.POST('/api/auth/signup', { body }).then(unwrap),
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
