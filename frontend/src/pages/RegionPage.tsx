import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { regionMonthlyVolumesQuery, regionsQuery } from '../api/queries'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { Loading } from '../components/Loading'
import { PeriodSelector } from '../components/PeriodSelector'
import { VOLUME_CHART_HEIGHT, VolumeChart, VolumeLegend, VolumeTable } from '../components/VolumeChart'
import { addMonths, chartRange } from '../lib/month'
import { fillVolumeMonths, seoulMonth } from '../lib/series'
import { useDocumentTitle } from '../lib/useDocumentTitle'
import { usePeriodParam } from '../lib/usePeriodParam'

/** 서버 최대 기간 (RegionQueryService.MAX_MONTHS) */
const MAX_MONTHS = 120

export function RegionPage() {
  const sggCd = useParams().sggCd!
  const [period, setPeriod] = usePeriodParam()
  const regions = useQuery(regionsQuery)
  const region = regions.data?.find((item) => item.sggCd === sggCd)
  useDocumentTitle(region ? `${region.sggName} 거래량` : null)

  // to를 같이 보낸다. 생략하면 서버가 기준일의 달을 끝으로 쓰는데, 서버가 클라이언트보다 한 달 앞서면(월말 자정 근처)
  // from부터 121개월이 되어 400이 난다. 한계: 사용자 PC 시계가 크게 틀리면 여전히 어긋날 수 있다
  const [to] = useState(() => seoulMonth())
  const from = addMonths(to, -(MAX_MONTHS - 1))
  const volumes = useQuery(regionMonthlyVolumesQuery(sggCd, from, to))

  if (volumes.isError) {
    return <ErrorState error={volumes.error} notFoundTitle="구를 찾을 수 없어요" onRetry={() => volumes.refetch()} />
  }

  const name = region?.sggName ?? sggCd
  let body
  if (volumes.data === undefined) {
    body = (
      <div style={{ height: VOLUME_CHART_HEIGHT }} className="flex items-center justify-center">
        <Loading label="거래량 불러오는 중" />
      </div>
    )
  } else {
    const { months, baseDate, provisionalFrom } = volumes.data
    const range = chartRange(period, baseDate, months)
    const rows = fillVolumeMonths(months, range.from, range.to, provisionalFrom)
    body = rows.some((row) => row.count + row.bulkCount > 0) ? (
      <>
        <figure aria-label="월별 거래량" className="rounded-xl border border-slate-200 bg-white px-2 pt-3 pb-1">
          <VolumeChart rows={rows} />
        </figure>
        <VolumeLegend />
        <VolumeTable rows={rows} />
      </>
    ) : (
      <EmptyState title="이 기간에는 거래가 없어요">기간을 늘려 보세요.</EmptyState>
    )
  }

  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-2xl font-bold text-slate-900">{name} 거래량</h1>
        <p className="text-sm text-slate-600">아파트 매매 월별 계약 건수 (해제 거래 제외)</p>
        <Link
          to={`/?sggCd=${sggCd}`}
          className="inline-block pt-1 text-sm font-medium text-blue-700 underline-offset-2 hover:underline"
        >
          {name} 단지 검색 →
        </Link>
      </header>
      <div className="space-y-2">
        <PeriodSelector value={period} onChange={setPeriod} />
        {body}
      </div>
    </div>
  )
}
