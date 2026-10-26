import type { Schemas } from '../api/client'
import { formatDate, formatManwon } from '../lib/format'
import { formatDong, formatFloor, registrationLabel } from '../lib/trade'

type Trade = Schemas['TradeItem']

/** 모바일은 카드, md 이상은 표. 같은 데이터를 두 번 그리고 CSS로 하나만 보인다 */
export function TradeList({ trades }: { trades: readonly Trade[] }) {
  return (
    <>
      <ul aria-label="거래 목록" className="space-y-2 md:hidden">
        {trades.map((trade) => (
          <li key={trade.id}>
            <TradeCard trade={trade} />
          </li>
        ))}
      </ul>
      <table className="hidden w-full overflow-hidden rounded-xl border border-slate-200 bg-white text-sm md:table">
        <thead className="bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th className="px-3 py-2 font-medium">계약일</th>
            <th className="px-3 py-2 text-right font-medium">거래금액</th>
            <th className="px-3 py-2 text-right font-medium">전용면적</th>
            <th className="px-3 py-2 text-right font-medium">층</th>
            <th className="px-3 py-2 font-medium">동</th>
            <th className="px-3 py-2 font-medium">거래 유형</th>
            <th className="px-3 py-2 font-medium">등기일</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {trades.map((trade) => (
            <tr key={trade.id}>
              <td className="px-3 py-2.5 whitespace-nowrap text-slate-600 tabular-nums">{formatDate(trade.dealDate)}</td>
              <td className="px-3 py-2.5 text-right whitespace-nowrap">
                {trade.bulk && <BulkBadge />}
                <span className="font-semibold text-slate-900">{formatManwon(trade.dealAmount)}</span>
              </td>
              <td className="px-3 py-2.5 text-right whitespace-nowrap text-slate-600 tabular-nums">{trade.exclusiveArea}㎡</td>
              <td className="px-3 py-2.5 text-right whitespace-nowrap text-slate-600">{formatFloor(trade.floor)}</td>
              <td className="px-3 py-2.5 whitespace-nowrap text-slate-600">{trade.aptDong ? formatDong(trade.aptDong) : '-'}</td>
              <td className="px-3 py-2.5 whitespace-nowrap text-slate-600">{trade.dealingGbn ?? '-'}</td>
              <td className="px-3 py-2.5 whitespace-nowrap text-slate-600 tabular-nums">{registrationLabel(trade) ?? '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  )
}

function TradeCard({ trade }: { trade: Trade }) {
  const place = [`${trade.exclusiveArea}㎡`, formatFloor(trade.floor), trade.aptDong && formatDong(trade.aptDong)]
  const registration = registrationLabel(trade)
  // 모바일은 좁아서 모르는 값(-)은 빼고 아는 것만
  const extra = [trade.dealingGbn, registration && (registration === '등기 전' ? registration : `등기 ${registration}`)]
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="flex items-baseline justify-between gap-3">
        <p className="text-lg font-semibold text-slate-900">
          {formatManwon(trade.dealAmount)}
          {trade.bulk && <BulkBadge className="ml-2" />}
        </p>
        <p className="shrink-0 text-sm text-slate-500 tabular-nums">{formatDate(trade.dealDate)}</p>
      </div>
      <p className="mt-1 text-sm text-slate-600">{place.filter(Boolean).join(' · ')}</p>
      {extra.some(Boolean) && <p className="mt-0.5 text-xs text-slate-500">{extra.filter(Boolean).join(' · ')}</p>}
    </div>
  )
}

function BulkBadge({ className = 'mr-2' }: { className?: string }) {
  return (
    <span
      className={`inline-block rounded bg-amber-100 px-1.5 py-0.5 align-middle text-xs font-medium text-amber-800 ${className}`}
    >
      일괄
    </span>
  )
}
