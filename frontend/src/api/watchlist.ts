import { useMutation, useQueryClient } from '@tanstack/react-query'
import { watchlistQuery } from './queries'
import { api, ensureOk } from './client'

/** PUT /api/me/watchlist/{aptSeq}. 이미 있어도 204, 없는 단지는 404, 50개가 차 있으면 409 */
export function useAddToWatchlist(aptSeq: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: ['me', 'watchlist', 'add'],
    mutationFn: async () => {
      const result = await api.PUT('/api/me/watchlist/{aptSeq}', {
        params: { path: { aptSeq } },
      })
      ensureOk(result)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: watchlistQuery.queryKey }),
  })
}

/** DELETE /api/me/watchlist/{aptSeq}. 목록에 없어도 204 */
export function useRemoveFromWatchlist(aptSeq: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: ['me', 'watchlist', 'remove'],
    mutationFn: async () => {
      const result = await api.DELETE('/api/me/watchlist/{aptSeq}', {
        params: { path: { aptSeq } },
      })
      ensureOk(result)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: watchlistQuery.queryKey }),
  })
}
