import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import type { Schemas } from '../api/client'
import { complexDetailQuery, complexTradesQuery } from '../api/queries'
import { AreaTabs } from '../components/AreaTabs'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { Loading } from '../components/Loading'
import { Pagination } from '../components/Pagination'
import { PriceTrend } from '../components/PriceTrend'
import { TradeList } from '../components/TradeList'
import { parsePage, pickAreaGroup, searchWith } from '../lib/complexParams'
import { useDocumentTitle } from '../lib/useDocumentTitle'

/**
 * 면적(?area=)·페이지(?page=)는 URL이 기준이다.
 * ?area=가 없거나 단지에 없는 값이면 거래 수 최다 그룹으로 replace한다. 잘못된 주소가 history에 남지 않게.
 */
export function ComplexPage() {
  const aptSeq = useParams().aptSeq!
  const [params, setParams] = useSearchParams()
  const detail = useQuery(complexDetailQuery(aptSeq))
  useDocumentTitle(detail.data?.name)

  const rawArea = params.get('area')
  const area = detail.data ? pickAreaGroup(detail.data.areaGroups, rawArea) : null
  // URL이 보정된 값과 같아진 뒤에만 그 면적을 쓴다. 그 전에는 보정 전 주소로 요청하지 않는다
  const resolvedArea = area !== null && String(area) === rawArea ? area : null

  useEffect(() => {
    if (area === null || String(area) === rawArea) return
    setParams(
      (prev) => {
        const updated = new URLSearchParams(prev)
        updated.set('area', String(area))
        updated.delete('page')
        return updated
      },
      { replace: true },
    )
  }, [area, rawArea, setParams])

  if (detail.isError) {
    return <ErrorState error={detail.error} notFoundTitle="단지를 찾을 수 없어요" onRetry={() => detail.refetch()} />
  }
  if (detail.data === undefined) return <Loading />

  return (
    <div className="space-y-4">
      <ComplexHeader complex={detail.data} />
      {detail.data.bulkDominant && <BulkDominantNotice ratio={detail.data.bulkRatio} />}
      {detail.data.areaGroups.length === 0 ? (
        <EmptyState title="아직 거래 기록이 없어요" />
      ) : (
        resolvedArea !== null && (
          <>
            <AreaTabs
              groups={detail.data.areaGroups}
              selected={resolvedArea}
              searchFor={(group) => searchWith(params, { area: String(group), page: null })}
            />
            <PriceTrend
              aptSeq={aptSeq}
              group={detail.data.areaGroups.find((group) => group.areaGroup === resolvedArea)!}
              bulkDominant={detail.data.bulkDominant}
            />
            <TradeSection
              aptSeq={aptSeq}
              area={resolvedArea}
              page={parsePage(params.get('page'))}
              searchFor={(page) => searchWith(params, { page: page === 1 ? null : String(page) })}
            />
          </>
        )
      )}
    </div>
  )
}

type TradeSectionProps = {
  aptSeq: string
  area: number
  /** 1부터 */
  page: number
  searchFor: (page: number) => string
}

/** 거래 목록. 오류는 이 영역에만 보여주고 헤더·탭은 그대로 둔다 */
function TradeSection({ aptSeq, area, page, searchFor }: TradeSectionProps) {
  const trades = useQuery(complexTradesQuery(aptSeq, area, page - 1))

  let body
  if (trades.isError) {
    body = <ErrorState error={trades.error} onRetry={() => trades.refetch()} />
  } else if (trades.data === undefined) {
    body = <Loading label="거래 불러오는 중" />
  } else if (trades.data.content.length === 0) {
    body =
      trades.data.totalElements === 0 ? (
        <EmptyState title="이 면적의 거래가 없어요" />
      ) : (
        <EmptyState title="이 페이지에 거래가 없어요">
          <Link to={{ search: searchFor(1) }} className="font-medium text-blue-700 underline-offset-2 hover:underline">
            첫 페이지로
          </Link>
        </EmptyState>
      )
  } else {
    body = (
      <div className={trades.isPlaceholderData ? 'opacity-50 transition-opacity' : undefined}>
        <TradeList trades={trades.data.content} />
        <Pagination page={page} totalPages={trades.data.totalPages} searchFor={searchFor} />
      </div>
    )
  }

  return (
    <section aria-labelledby="trades-heading" className="space-y-2">
      <div className="flex items-baseline justify-between gap-3">
        <h2 id="trades-heading" className="font-semibold text-slate-900">
          거래 내역
        </h2>
        {trades.data && (
          <span className="text-sm text-slate-500">{trades.data.totalElements.toLocaleString('ko-KR')}건</span>
        )}
      </div>
      <p className="text-xs text-slate-500">최신 계약일 순. 일괄: 같은 단지·같은 날 10건 이상인 거래</p>
      {body}
    </section>
  )
}

function ComplexHeader({ complex }: { complex: Schemas['ComplexDetail'] }) {
  const location = [complex.sggName, complex.umdName].filter(Boolean).join(' ')
  return (
    <header className="space-y-1">
      <h1 className="text-2xl font-bold text-slate-900">{complex.name}</h1>
      <p className="text-sm text-slate-600">
        {location}
        {complex.buildYear !== null && ` · ${complex.buildYear}년 준공`}
      </p>
      {complex.roadAddress && <p className="text-sm text-slate-500">{complex.roadAddress}</p>}
      <Link
        to={`/regions/${complex.sggCd}`}
        className="inline-block pt-1 text-sm font-medium text-blue-700 underline-offset-2 hover:underline"
      >
        {complex.sggName} 거래량 보기 →
      </Link>
    </header>
  )
}

/** 일괄 거래 위주 단지 안내. C에서 가격 추이 대신 보여줄 안내와 같은 문구를 쓴다 */
function BulkDominantNotice({ ratio }: { ratio: number }) {
  return (
    <div role="note" className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
      거래의 {Math.round(ratio * 100)}%가 일괄 거래(같은 단지·같은 날 10건 이상)예요. 일반 시세와 다를 수 있어요.
    </div>
  )
}
