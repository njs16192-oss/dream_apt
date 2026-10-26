import { Bar, BarChart, CartesianGrid, Rectangle, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { CHART } from '../lib/chartTheme'
import type { YearMonth } from '../lib/month'
import { monthTicks, niceTicks, type VolumeRow } from '../lib/series'

const count = new Intl.NumberFormat('ko-KR')

type ShapeProps = { x?: number; y?: number; width?: number; height?: number; payload?: VolumeRow }

/** 일반 거래(아래 조각). 위에 일괄 조각이 없으면 이 조각이 맨 위라 위쪽 모서리를 둥글게 한다 */
function NormalBar(props: ShapeProps) {
  const { payload } = props
  return (
    <Rectangle
      {...props}
      fill={CHART.primary}
      fillOpacity={payload?.provisional ? CHART.provisionalOpacity : 1}
      radius={payload?.bulkCount === 0 ? [4, 4, 0, 0] : 0}
    />
  )
}

/**
 * 일괄 거래(위 조각). 아래 조각과 사이에 2px 바탕색 간격을 두려고 아래쪽을 2px 줄인다.
 * 테두리(stroke)로 간격을 만들면 좁은 화면에서 2–5px 폭 막대가 지워진다.
 */
function BulkBar(props: ShapeProps) {
  const { payload, height = 0 } = props
  if (height <= 0) return null
  const gap = payload && payload.count > 0 && height > 3 ? 2 : 0
  return (
    <Rectangle
      {...props}
      height={height - gap}
      fill={CHART.bulk}
      fillOpacity={payload?.provisional ? CHART.provisionalOpacity : 1}
      radius={[4, 4, 0, 0]}
    />
  )
}

export const VOLUME_CHART_HEIGHT = 260

/** 월별 거래량. 일반(아래) + 일괄(위) 쌓은 막대, y축은 0부터 (막대는 길이로 값을 읽는다) */
export function VolumeChart({ rows }: { rows: readonly VolumeRow[] }) {
  const { ticks, format } = monthTicks(rows[0].month, rows[rows.length - 1].month)
  const yTicks = niceTicks([0, ...rows.map((row) => row.count + row.bulkCount)])
  const rowsByMonth = new Map(rows.map((row) => [row.month, row]))
  return (
    <div style={{ height: VOLUME_CHART_HEIGHT }} aria-hidden>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={rows as VolumeRow[]} margin={{ top: 8, right: 20, bottom: 0, left: 0 }} barCategoryGap="20%">
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
            domain={[0, yTicks[yTicks.length - 1]]}
            ticks={yTicks}
            tickFormatter={(value: number) => count.format(value)}
            width={48}
            tickLine={false}
            axisLine={false}
            tick={{ fill: CHART.axisText, fontSize: 12 }}
          />
          <Tooltip
            cursor={{ fill: 'rgba(15, 23, 42, 0.05)' }}
            content={({ active, label }) => {
              const row = active ? rowsByMonth.get(label as YearMonth) : undefined
              return row ? <VolumeTooltip row={row} /> : null
            }}
          />
          <Bar dataKey="count" stackId="volume" maxBarSize={24} shape={NormalBar} isAnimationActive={false} />
          <Bar dataKey="bulkCount" stackId="volume" maxBarSize={24} shape={BulkBar} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

function VolumeTooltip({ row }: { row: VolumeRow }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs shadow-sm">
      <p className="text-slate-500">
        {row.month.replace('-', '.')}
        {row.provisional && ' · 집계 중'}
      </p>
      <p className="mt-0.5 text-sm font-semibold text-slate-900">{count.format(row.count + row.bulkCount)}건</p>
      <p className="text-slate-500">
        일반 {count.format(row.count)}건 · 일괄 {count.format(row.bulkCount)}건
      </p>
    </div>
  )
}

/** 막대 범례: 색 상자(막대 모양) + 집계 중 설명 */
export function VolumeLegend() {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500">
      <li className="flex items-center gap-1.5">
        <Swatch color={CHART.primary} />
        일반 거래
      </li>
      <li className="flex items-center gap-1.5">
        <Swatch color={CHART.bulk} />
        일괄 거래 (같은 단지·같은 날 10건 이상)
      </li>
      <li className="flex items-center gap-1.5">
        <Swatch color={CHART.primary} opacity={CHART.provisionalOpacity} />
        흐린 막대는 집계 중 (최근 2개월, 신고가 더 들어올 수 있음)
      </li>
    </ul>
  )
}

function Swatch({ color, opacity = 1 }: { color: string; opacity?: number }) {
  return <span aria-hidden className="size-2.5 shrink-0 rounded-sm" style={{ backgroundColor: color, opacity }} />
}

/** 표 보기: 기간 안의 모든 달(0건 포함) 최신순 */
export function VolumeTable({ rows }: { rows: readonly VolumeRow[] }) {
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-slate-600 select-none">표로 보기</summary>
      <div className="mt-2 overflow-hidden rounded-xl border border-slate-200 bg-white">
        <table className="w-full">
          <caption className="sr-only">월별 거래량</caption>
          <thead className="bg-slate-50 text-left text-xs text-slate-500">
            <tr>
              <th className="px-3 py-2 font-medium">계약월</th>
              <th className="px-3 py-2 text-right font-medium">일반</th>
              <th className="px-3 py-2 text-right font-medium">일괄</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {[...rows].reverse().map((row) => (
              <tr key={row.month}>
                <td className="px-3 py-2 text-slate-600 tabular-nums">
                  {row.month.replace('-', '.')}
                  {row.provisional && <span className="ml-1.5 text-xs text-slate-400">집계 중</span>}
                </td>
                <td className="px-3 py-2 text-right text-slate-900 tabular-nums">{count.format(row.count)}건</td>
                <td className="px-3 py-2 text-right text-slate-600 tabular-nums">{count.format(row.bulkCount)}건</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  )
}
