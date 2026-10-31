import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { useCallback, useEffect } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import type { Schemas } from '../api/client'
import { NEARBY_LIMIT, nearbyComplexesQuery } from '../api/queries'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { Loading } from '../components/Loading'
import { MapView } from '../components/MapView'
import { formatDistance } from '../lib/format'
import { isCanonical, mapSearchParams, parseMapParams, RADII, type Radius } from '../lib/mapParams'
import { useDocumentTitle } from '../lib/useDocumentTitle'

/**
 * 반경 안의 단지: 지도 + 거리순 목록 (ADR 010 ⑧).
 * 중심·반경은 URL(?lat=&lng=&r=)에 둔다. 지도 클릭·반경 선택은 보기 설정이라 replace로 바꾼다 (ADR 008).
 * 주소가 없거나 잘못되면 기본값(서울시청, 1km)으로 replace한다. parseMapParams가 보정된 값을 돌려주므로
 * 보정 전 주소로 읽어도 쿼리 키가 같아 요청은 한 번이다.
 */
export function MapPage() {
  const [params, setParams] = useSearchParams()
  const state = parseMapParams(params)
  const canonical = isCanonical(params)
  const navigate = useNavigate()
  useDocumentTitle('주변 단지')

  useEffect(() => {
    if (!canonical) setParams(mapSearchParams(parseMapParams(params)), { replace: true })
  }, [canonical, params, setParams])

  const nearby = useQuery(nearbyComplexesQuery(state.lat, state.lng, state.radius))

  const changeCenter = useCallback(
    (lat: number, lng: number) => setParams(mapSearchParams({ lat, lng, radius: state.radius }), { replace: true }),
    [setParams, state.radius],
  )
  const changeRadius = (radius: Radius) => setParams(mapSearchParams({ ...state, radius }), { replace: true })
  const select = useCallback((aptSeq: string) => navigate(`/complexes/${encodeURIComponent(aptSeq)}`), [navigate])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-bold">주변 단지</h1>
        <RadiusSelector value={state.radius} onChange={changeRadius} />
      </div>

      {/* Leaflet은 pane·컨트롤에 z-index(400–1000)를 쓴다. isolate로 가둬 헤더 위로 올라오지 않게 한다 */}
      <div className="isolate overflow-hidden rounded-xl border border-slate-200">
        <MapView
          center={state}
          radius={state.radius}
          items={nearby.data?.items ?? []}
          onCenterChange={changeCenter}
          onSelect={select}
        />
      </div>
      <p className="text-xs text-slate-500">지도를 누르면 그 위치를 중심으로 다시 찾아요. 위치는 단지 주출입구 기준이에요.</p>

      <NearbyList query={nearby} radius={state.radius} />
    </div>
  )
}

function RadiusSelector({ value, onChange }: { value: Radius; onChange: (next: Radius) => void }) {
  return (
    <div role="group" aria-label="반경" className="inline-flex rounded-lg border border-slate-200 bg-white p-0.5 text-sm">
      {RADII.map((radius) => (
        <button
          key={radius}
          type="button"
          aria-pressed={radius === value}
          onClick={() => onChange(radius)}
          className={`rounded-md px-3 py-1 font-medium transition-colors ${
            radius === value ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-100'
          }`}
        >
          {formatDistance(radius)}
        </button>
      ))}
    </div>
  )
}

function NearbyList({ query, radius }: { query: UseQueryResult<Schemas['NearbyComplexes']>; radius: number }) {
  if (query.isError) {
    return <ErrorState error={query.error} onRetry={() => query.refetch()} />
  }
  if (query.data === undefined) {
    return <Loading label="주변 단지 불러오는 중" />
  }
  const { items, totalCount } = query.data
  if (items.length === 0) {
    return (
      <EmptyState title="반경 안에 단지가 없어요">반경을 넓히거나 지도에서 다른 곳을 눌러 보세요.</EmptyState>
    )
  }
  return (
    <section aria-label="주변 단지 목록" className={query.isPlaceholderData ? 'opacity-50 transition-opacity' : undefined}>
      <p className="mb-2 text-sm text-slate-600">
        반경 {formatDistance(radius)} 안 {totalCount.toLocaleString('ko-KR')}개
        {totalCount > items.length &&
          ` 중 가까운 ${items.length.toLocaleString('ko-KR')}개만 보여요 (최대 ${NEARBY_LIMIT}개)`}
      </p>
      <ul className="space-y-2">
        {items.map((item) => (
          <li key={item.aptSeq}>
            <NearbyItem item={item} />
          </li>
        ))}
      </ul>
    </section>
  )
}

function NearbyItem({ item }: { item: Schemas['NearbyComplex'] }) {
  const location = [item.sggName, item.umdName].filter(Boolean).join(' ')
  return (
    <Link
      to={`/complexes/${encodeURIComponent(item.aptSeq)}`}
      className="flex items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white p-4 transition-colors hover:border-slate-400"
    >
      <div className="min-w-0">
        <p className="truncate font-semibold text-slate-900">{item.name}</p>
        <p className="mt-0.5 text-sm text-slate-500">
          {formatDistance(item.distanceM)} · {location}
          {item.buildYear !== null && ` · ${item.buildYear}년 준공`}
        </p>
      </div>
      <div className="shrink-0 text-right">
        <p className="text-xs text-slate-500">최근 12개월</p>
        <p className="text-sm font-medium text-slate-800">{item.recentTradeCount.toLocaleString('ko-KR')}건</p>
      </div>
    </Link>
  )
}
