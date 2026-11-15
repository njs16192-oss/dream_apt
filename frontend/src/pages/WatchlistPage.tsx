import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import type { Schemas } from '../api/client'
import { WATCHLIST_MAX, watchlistQuery } from '../api/queries'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { Loading } from '../components/Loading'
import { formatDate } from '../lib/format'
import { useDocumentTitle } from '../lib/useDocumentTitle'

/** 내 관심단지 목록. 로그인 확인은 RequireLogin이 한다 (AppRoutes) */
export function WatchlistPage() {
  useDocumentTitle('관심단지')
  const watchlist = useQuery(watchlistQuery)

  let body
  if (watchlist.isError) {
    body = <ErrorState error={watchlist.error} onRetry={() => watchlist.refetch()} />
  } else if (watchlist.data === undefined) {
    body = <Loading />
  } else if (watchlist.data.length === 0) {
    body = (
      <EmptyState title="관심단지가 없어요">
        <p>단지 상세 화면에서 ☆ 버튼을 눌러 추가하세요.</p>
        <Link to="/" className="mt-2 inline-block font-medium text-blue-700 underline-offset-2 hover:underline">
          단지 검색으로 가기
        </Link>
      </EmptyState>
    )
  } else {
    body = (
      <ul className="divide-y divide-slate-200 rounded-xl border border-slate-200 bg-white">
        {watchlist.data.map((item) => (
          <WatchlistRow key={item.aptSeq} item={item} />
        ))}
      </ul>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between gap-3">
        <h1 className="text-2xl font-bold text-slate-900">관심단지</h1>
        {watchlist.data && (
          <span className="text-sm text-slate-500 tabular-nums">
            {watchlist.data.length} / {WATCHLIST_MAX}
          </span>
        )}
      </div>
      {body}
    </div>
  )
}

function WatchlistRow({ item }: { item: Schemas['WatchlistItem'] }) {
  return (
    <li className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 px-4 py-3">
      <div className="min-w-0">
        <Link
          to={`/complexes/${item.aptSeq}`}
          className="font-medium text-slate-900 underline-offset-2 hover:underline"
        >
          {item.name}
        </Link>
        <span className="ml-2 text-sm text-slate-500">{item.sggName}</span>
      </div>
      <span className="text-sm text-slate-600">
        {/* 수집 범위가 2020년부터라 "거래 없음"이 아니라 그 이후에 없다는 뜻이다 */}
        {item.lastDealDate === null ? '2020년 이후 거래 없음' : `최근 거래 ${formatDate(item.lastDealDate)}`}
      </span>
    </li>
  )
}
