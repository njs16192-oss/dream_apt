import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api, ensureOk } from './client'
import { unreadCountQuery } from './queries'

/**
 * 읽음 처리 뒤 다시 받을 것: 알림 목록 모든 페이지(['me','notifications'] 접두사)와 안 읽은 수.
 * 둘을 함께 기다려서, 처리 중 상태가 목록이 새로 온 뒤에 풀리게 한다.
 */
function refreshNotifications(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['me', 'notifications'] }),
    queryClient.invalidateQueries({ queryKey: unreadCountQuery.queryKey }),
  ])
}

/*
 * onSuccess에서만 다시 받는다 (관심단지는 onSettled).
 * 관심단지 버튼은 지금 상태로 PUT/DELETE를 고르는 토글이라, 실패 뒤 상태가 서버와 어긋나면 다음 클릭이 반대로 간다.
 * 읽음 처리는 방향이 하나뿐이고 여러 번 보내도 결과가 같다(서버가 204). 실패 뒤 다시 눌러도 해가 없으니
 * 실패할 때마다 목록 전체를 다시 받을 이유가 없다. 401은 MutationCache가 따로 처리한다 (mutationKey가 ['me', …]).
 */

/** PUT /api/me/notifications/{id}/read. 이미 읽었어도 204, 내 알림이 아니거나 없으면 404 */
export function useMarkRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: ['me', 'notifications', 'read'],
    mutationFn: async (id: number) =>
      ensureOk(await api.PUT('/api/me/notifications/{id}/read', { params: { path: { id } } })),
    onSuccess: () => refreshNotifications(queryClient),
  })
}

/** PUT /api/me/notifications/read-all */
export function useMarkAllRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: ['me', 'notifications', 'read-all'],
    mutationFn: async () => ensureOk(await api.PUT('/api/me/notifications/read-all')),
    onSuccess: () => refreshNotifications(queryClient),
  })
}
