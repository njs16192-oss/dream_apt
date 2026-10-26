import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { Schemas } from '../api/client'
import { CHART } from '../lib/chartTheme'
import { formatManwon } from '../lib/format'
import type { YearMonth } from '../lib/month'
import { formatEokAxis, isolatedMonths, monthTicks, niceTicks, type SplitRow } from '../lib/series'

type Row = SplitRow<Schemas['MonthlyPrice']>

export const PRICE_CHART_HEIGHT = 260

/** 월별 중앙값 선. rows는 기간 범위로 채우고(fillMonths) 나눈(splitProvisional) 행 */
export function PriceChart({ rows, provisionalFrom }: { rows: readonly Row[]; provisionalFrom: YearMonth }) {
  const { ticks, format } = monthTicks(rows[0].month, rows[rows.length - 1].month)
  const yTicks = niceTicks(rows.flatMap((row) => (row.item ? [row.item.medianAmount] : [])))
  const isolated = isolatedMonths(rows, provisionalFrom)
  const rowsByMonth = new Map(rows.map((row) => [row.month, row]))

  // 앞뒤가 빈 달은 선이 안 그려지므로 점으로. 집계 중 달은 점선 쪽에서 흐리게 (경계 달은 확정)
  const isolatedDot = (lineIsProvisional: boolean) =>
    function IsolatedDot(props: { cx?: number; cy?: number; payload?: Row }) {
      const month = props.payload?.month ?? ''
      const mark = isolated.get(month)
      if (!mark || mark.provisional !== lineIsProvisional || props.cx === undefined || props.cy === undefined) {
        return <g key={month} />
      }
      return (
        <circle
          key={month}
          cx={props.cx}
          cy={props.cy}
          r={4}
          fill={CHART.primary}
          fillOpacity={lineIsProvisional ? CHART.provisionalOpacity : 1}
          stroke={CHART.surface}
          strokeWidth={2}
        />
      )
    }

  return (
    <div style={{ height: PRICE_CHART_HEIGHT }} aria-hidden>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={rows as Row[]} margin={{ top: 8, right: 20, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke={CHART.grid} />
          <XAxis
            dataKey="month"
            ticks={ticks}
            tickFormatter={format}
            interval={0}
            tickLine={false}
            axisLine={{ stroke: CHART.axis }}
            tick={{ fill: CHART.axisText, fontSize: 12 }}
          />
          <YAxis
            // 0에서 시작하지 않는다: 24억 근처에서 움직이는 선이 위쪽에 납작하게 붙지 않게
            domain={[yTicks[0], yTicks[yTicks.length - 1]]}
            ticks={yTicks}
            tickFormatter={formatEokAxis}
            width={56}
            tickLine={false}
            axisLine={false}
            tick={{ fill: CHART.axisText, fontSize: 12 }}
          />
          <Tooltip
            cursor={{ stroke: CHART.axis }}
            content={({ active, label }) => {
              const row = active ? rowsByMonth.get(label as YearMonth) : undefined
              return row ? <PriceTooltip row={row} /> : null
            }}
          />
          <Line
            dataKey="confirmed"
            stroke={CHART.primary}
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
            connectNulls={false}
            dot={isolatedDot(false)}
            activeDot={{ r: 4, stroke: CHART.surface, strokeWidth: 2 }}
            isAnimationActive={false}
          />
          <Line
            dataKey="provisional"
            stroke={CHART.primary}
            strokeOpacity={CHART.provisionalOpacity}
            strokeWidth={2}
            strokeDasharray="4 4"
            connectNulls={false}
            dot={isolatedDot(true)}
            activeDot={false}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

/** 경계 달은 두 선에 다 있지만 월 기준으로 한 번만 보여준다 */
function PriceTooltip({ row }: { row: Row }) {
  const { item } = row
  return (
    <div className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs shadow-sm">
      <p className="text-slate-500">
        {row.month.replace('-', '.')}
        {item?.provisional && ' · 집계 중'}
      </p>
      {item ? (
        <>
          <p className="mt-0.5 text-sm font-semibold text-slate-900">{formatManwon(item.medianAmount)}</p>
          <p className="text-slate-500">중앙값 · {item.count}건</p>
        </>
      ) : (
        <p className="mt-0.5 text-slate-700">거래 없음</p>
      )}
    </div>
  )
}

/** 선 모양 범례. 시리즈가 하나라 색 상자 대신 실선·점선이 무엇인지 설명한다 */
export function PriceLegend() {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500">
      <li className="flex items-center gap-1.5">
        <LineKey />
        월별 중앙값 (일괄 거래 제외)
      </li>
      <li className="flex items-center gap-1.5">
        <LineKey dashed />
        집계 중 (최근 2개월, 신고가 더 들어올 수 있음)
      </li>
    </ul>
  )
}

function LineKey({ dashed = false }: { dashed?: boolean }) {
  return (
    <svg width="20" height="2" aria-hidden className="shrink-0">
      <line
        x1="0"
        y1="1"
        x2="20"
        y2="1"
        stroke={CHART.primary}
        strokeWidth="2"
        strokeDasharray={dashed ? '4 4' : undefined}
        strokeOpacity={dashed ? CHART.provisionalOpacity : 1}
      />
    </svg>
  )
}

/** 표 보기: 거래 있는 달만 최신순. 차트를 못 보는 경우(스크린 리더 등)의 대체이기도 하다 */
export function PriceTable({ rows }: { rows: readonly Row[] }) {
  const withData = rows.filter((row) => row.item !== null).reverse()
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-slate-600 select-none">표로 보기</summary>
      <div className="mt-2 overflow-hidden rounded-xl border border-slate-200 bg-white">
        <table className="w-full">
          <caption className="sr-only">월별 가격</caption>
          <thead className="bg-slate-50 text-left text-xs text-slate-500">
            <tr>
              <th className="px-3 py-2 font-medium">계약월</th>
              <th className="px-3 py-2 text-right font-medium">중앙값</th>
              <th className="px-3 py-2 text-right font-medium">거래 수</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {withData.map(({ month, item }) => (
              <tr key={month}>
                <td className="px-3 py-2 text-slate-600 tabular-nums">
                  {month.replace('-', '.')}
                  {item!.provisional && <span className="ml-1.5 text-xs text-slate-400">집계 중</span>}
                </td>
                <td className="px-3 py-2 text-right font-medium text-slate-900">{formatManwon(item!.medianAmount)}</td>
                <td className="px-3 py-2 text-right text-slate-600 tabular-nums">{item!.count}건</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  )
}
