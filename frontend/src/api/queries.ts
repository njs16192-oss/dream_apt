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

/** 거래 목록 한 페이지 건수 */
export const TRADE_PAGE_SIZE = 20

export function complexDetailQuery(aptSeq: string) {
  return queryOptions({
    queryKey: ['complexes', aptSeq],
    queryFn: () => api.GET('/api/complexes/{aptSeq}', { params: { path: { aptSeq } } }).then(unwrap),
  })
}

/** page는 API 기준(0부터). */
export function complexTradesQuery(aptSeq: string, areaGroup: number, page: number) {
  const queryKey = ['complexes', aptSeq, 'trades', areaGroup, page] as const
  return queryOptions({
    queryKey,
    queryFn: () =>
      api
        .GET('/api/complexes/{aptSeq}/trades', {
          params: { path: { aptSeq }, query: { areaGroup, page, size: TRADE_PAGE_SIZE } },
        })
        .then(unwrap),
    // 같은 단지·같은 면적에서 페이지만 넘길 때 이전 목록을 남긴다 (흐리게 표시).
    // 면적이 바뀌면 남기지 않는다: 다른 면적의 거래가 잠깐 보이면 헷갈린다
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[1] === aptSeq && previousQuery.queryKey[3] === areaGroup ? previous : undefined,
  })
}

/** 면적 그룹 월별 가격. 전체 기간을 한 번 받아 화면에서 1년/3년/5년/전체로 자른다 (ADR 008 ②) */
export function complexMonthlyPricesQuery(aptSeq: string, areaGroup: number) {
  return queryOptions({
    queryKey: ['complexes', aptSeq, 'areas', areaGroup, 'monthly'],
    queryFn: () =>
      api
        .GET('/api/complexes/{aptSeq}/areas/{areaGroup}/monthly', { params: { path: { aptSeq, areaGroup } } })
        .then(unwrap),
  })
}

/**
 * 구 월별 거래량. 서버 최대 120개월을 한 번에 받아 화면에서 자른다 (ADR 008 ②).
 * from·to는 호출하는 쪽에서 서울 기준 이번 달로 정해 둘 다 보낸다 (RegionPage 참고).
 */
export function regionMonthlyVolumesQuery(sggCd: string, from: string, to: string) {
  return queryOptions({
    queryKey: ['regions', sggCd, 'monthly', from, to],
    queryFn: () =>
      api.GET('/api/regions/{sggCd}/monthly', { params: { path: { sggCd }, query: { from, to } } }).then(unwrap),
  })
}
