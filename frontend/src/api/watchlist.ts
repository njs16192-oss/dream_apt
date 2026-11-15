import { useMutation } from '@tanstack/react-query'

/*
 * 관심단지 추가·해제 mutation. TODO(재승): 직접 작성 (PR 9a 커밋 4)
 *
 * 지킬 것
 * 1. mutationFn은 실패하면 ApiError를 던진다: client.ts의 ensureOk(await api.PUT(...)) 그대로 쓰면 된다
 *    - WatchButton이 409(50개 초과)를 status로 구분해 문구를 보여준다
 *    - 401(세션 만료)은 createQueryClient의 MutationCache가 잡아 로그아웃 상태로 바꾼다
 * 2. mutationKey를 ['me', …]로 단다. 예: ['me', 'watchlist', 'add']
 *    - 키가 없으면 1의 401 처리가 걸리지 않는다 (queryClient.ts의 isSessionExpired)
 * 3. 성공하면 ['me', 'watchlist'] 캐시가 서버와 같아지게 한다 (invalidate / setQueryData / 낙관적 갱신 중 선택)
 *    - 버튼 상태(aria-pressed)와 /watchlist 목록이 둘 다 이 캐시를 본다
 *
 * WatchButton은 mutate(), isPending, error, reset()만 쓴다. 변수 없이 부른다 (aptSeq는 hook 인자).
 * 지금 본문은 타입만 맞춘 자리다. 누르면 "미구현" 오류가 난다.
 */

/** PUT /api/me/watchlist/{aptSeq}. 이미 있어도 204, 없는 단지는 404, 50개가 차 있으면 409 */
export function useAddToWatchlist(aptSeq: string) {
  return useMutation<void, Error, void>({
    mutationFn: async () => {
      throw new Error(`TODO(재승): 관심단지 추가 미구현 (${aptSeq})`)
    },
  })
}

/** DELETE /api/me/watchlist/{aptSeq}. 목록에 없어도 204 */
export function useRemoveFromWatchlist(aptSeq: string) {
  return useMutation<void, Error, void>({
    mutationFn: async () => {
      throw new Error(`TODO(재승): 관심단지 해제 미구현 (${aptSeq})`)
    },
  })
}
