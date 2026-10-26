import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { api, unwrap } from './client'

/** 검색 결과 최대 건수. 더 보기가 없어서, 이만큼 오면 검색어를 좁히라고 안내한다. */
export const SEARCH_LIMIT = 20

export const regionsQuery = queryOptions({
  queryKey: ['regions'],
  queryFn: () => api.GET('/api/regions').then(unwrap),
  // 수집 대상 구 목록은 앱이 떠 있는 동안 바뀌지 않는다
  staleTime: Infinity,
})

/** term은 searchTerm()을 거친 값(trim, 자모만 아님). */
export function complexSearchQuery(term: string, sggCd: string | null) {
  return queryOptions({
    queryKey: ['complexes', 'search', term, sggCd],
    queryFn: () =>
      api
        .GET('/api/complexes', { params: { query: { q: term, sggCd: sggCd ?? undefined, limit: SEARCH_LIMIT } } })
        .then(unwrap),
    // 검색어가 바뀌는 동안 이전 결과를 남겨 둔다 (화면에서 흐리게 표시)
    placeholderData: keepPreviousData,
  })
}
