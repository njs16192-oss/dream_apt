import { Link } from 'react-router'
import type { Schemas } from '../api/client'

export function ComplexSearchResult({ item }: { item: Schemas['ComplexSearchItem'] }) {
  const location = [item.sggName, item.umdName].filter(Boolean).join(' ')
  return (
    <Link
      to={`/complexes/${encodeURIComponent(item.aptSeq)}`}
      className="flex items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white p-4 transition-colors hover:border-slate-400"
    >
      <div className="min-w-0">
        <p className="truncate font-semibold text-slate-900">{item.name}</p>
        <p className="mt-0.5 text-sm text-slate-500">
          {location}
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
