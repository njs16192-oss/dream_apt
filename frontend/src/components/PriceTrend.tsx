import { useQuery } from '@tanstack/react-query'
import type { Schemas } from '../api/client'
import { complexMonthlyPricesQuery } from '../api/queries'
import { chartRange, fillMonths } from '../lib/month'
import { splitProvisional } from '../lib/series'
import { usePeriodParam } from '../lib/usePeriodParam'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'
import { Loading } from './Loading'
import { PeriodSelector } from './PeriodSelector'
import { PRICE_CHART_HEIGHT, PriceChart, PriceLegend, PriceTable } from './PriceChart'

type Props = {
  aptSeq: string
  group: Schemas['AreaGroupSummary']
  bulkDominant: boolean
}

/**
 * 선택한 면적의 월별 가격 추이. 일괄 거래 위주 단지나 일반 거래가 없는 면적은 요청하지 않고 안내만 한다.
 * 면적을 바꾸면 다른 면적의 선이 남지 않게 이전 데이터를 쓰지 않는다 (고정 높이 로딩).
 */
export function PriceTrend({ aptSeq, group, bulkDominant }: Props) {
  const hidden = bulkDominant
    ? '일괄 거래 위주라 가격 추이를 보여주지 않아요'
    : group.tradeCount === 0
      ? '이 면적은 일반 거래가 없어요'
      : null

  return (
    <section aria-labelledby="price-heading" className="space-y-2">
      <h2 id="price-heading" className="font-semibold text-slate-900">
        가격 추이
      </h2>
      {hidden ? <EmptyState title={hidden} /> : <PriceTrendBody aptSeq={aptSeq} areaGroup={group.areaGroup} />}
    </section>
  )
}

function PriceTrendBody({ aptSeq, areaGroup }: { aptSeq: string; areaGroup: number }) {
  const [period, setPeriod] = usePeriodParam()
  const prices = useQuery(complexMonthlyPricesQuery(aptSeq, areaGroup))

  let body
  if (prices.isError) {
    body = <ErrorState error={prices.error} onRetry={() => prices.refetch()} />
  } else if (prices.data === undefined) {
    body = (
      <div style={{ height: PRICE_CHART_HEIGHT }} className="flex items-center justify-center">
        <Loading label="가격 불러오는 중" />
      </div>
    )
  } else {
    const { months, baseDate, provisionalFrom } = prices.data
    const { from, to } = chartRange(period, baseDate, months)
    const rows = splitProvisional(fillMonths(months, from, to), provisionalFrom, (item) => item.medianAmount)
    body = rows.some((row) => row.item !== null) ? (
      <>
        <figure aria-label="월별 가격 추이" className="rounded-xl border border-slate-200 bg-white px-2 pt-3 pb-1">
          <PriceChart rows={rows} provisionalFrom={provisionalFrom} />
        </figure>
        <PriceLegend />
        <PriceTable rows={rows} />
      </>
    ) : (
      <EmptyState title="이 기간에는 거래가 없어요">기간을 늘려 보세요.</EmptyState>
    )
  }

  return (
    <div className="space-y-2">
      <PeriodSelector value={period} onChange={setPeriod} />
      {body}
    </div>
  )
}
