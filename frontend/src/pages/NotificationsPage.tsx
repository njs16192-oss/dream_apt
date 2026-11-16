import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import type { Schemas } from '../api/client'
import { useMarkAllRead, useMarkRead } from '../api/notifications'
import { notificationsQuery, unreadCountQuery } from '../api/queries'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { Loading } from '../components/Loading'
import { Pagination } from '../components/Pagination'
import { parsePage, searchWith } from '../lib/complexParams'
import { formatArea, formatDate, formatManwon } from '../lib/format'
import { useDocumentTitle } from '../lib/useDocumentTitle'

const LINK_CLASS = 'font-medium text-blue-700 underline-offset-2 hover:underline'

/** 내 신고가 알림. 페이지(?page=, 1부터)는 URL이 기준이다. 로그인 확인은 RequireLogin이 한다 (AppRoutes) */
export function NotificationsPage() {
  useDocumentTitle('알림')
  const [params] = useSearchParams()
  const page = parsePage(params.get('page'))
  const searchFor = (target: number) => searchWith(params, { page: target === 1 ? null : String(target) })
  const notifications = useQuery(notificationsQuery(page - 1))
  const unread = useQuery(unreadCountQuery)
  const markAllRead = useMarkAllRead()

  let body
  if (notifications.isError) {
    body = <ErrorState error={notifications.error} onRetry={() => notifications.refetch()} />
  } else if (notifications.data === undefined) {
    body = <Loading />
  } else if (notifications.data.content.length === 0) {
    body =
      notifications.data.totalElements === 0 ? (
        <EmptyState title="아직 알림이 없어요">
          <p>관심단지에 2020년 이후 최고가 거래가 나오면 알려 드려요.</p>
          <Link to="/watchlist" className={`mt-2 inline-block ${LINK_CLASS}`}>
            관심단지 보기
          </Link>
        </EmptyState>
      ) : (
        <EmptyState title="이 페이지에 알림이 없어요">
          <Link to={{ search: searchFor(1) }} className={LINK_CLASS}>
            첫 페이지로
          </Link>
        </EmptyState>
      )
  } else {
    body = (
      <div className={notifications.isPlaceholderData ? 'opacity-50 transition-opacity' : undefined}>
        <ul className="divide-y divide-slate-200 overflow-hidden rounded-xl border border-slate-200 bg-white">
          {notifications.data.content.map((item) => (
            <NotificationRow key={item.id} item={item} />
          ))}
        </ul>
        <Pagination page={page} totalPages={notifications.data.totalPages} searchFor={searchFor} />
      </div>
    )
  }

  const unreadCount = unread.data?.count ?? 0
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-baseline gap-3">
          <h1 className="text-2xl font-bold text-slate-900">알림</h1>
          {unread.data && <span className="text-sm text-slate-500">안 읽은 알림 {unreadCount}개</span>}
        </div>
        <div className="flex flex-col items-end gap-1">
          <button
            type="button"
            onClick={() => markAllRead.mutate()}
            // 안 읽은 수를 모르거나 0이면 보낼 것이 없다
            disabled={unreadCount === 0 || markAllRead.isPending}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            {markAllRead.isPending ? '처리 중…' : '모두 읽음'}
          </button>
          {markAllRead.isError && (
            <p role="alert" className="text-xs text-red-600">
              모두 읽음 처리하지 못했어요. 잠시 후 다시 시도해 주세요.
            </p>
          )}
        </div>
      </div>
      {body}
    </div>
  )
}

/** 알림 한 건. 읽음 처리 상태가 행마다 따로라 hook을 행 안에서 쓴다 */
function NotificationRow({ item }: { item: Schemas['NotificationItem'] }) {
  const markRead = useMarkRead()
  const markIfUnread = () => {
    if (!item.read) markRead.mutate(item.id)
  }

  return (
    <li className={`space-y-1 px-4 py-3 ${item.read ? '' : 'bg-blue-50/60'}`}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        {!item.read && (
          <span className="size-2 shrink-0 rounded-full bg-blue-600">
            <span className="sr-only">안 읽음</span>
          </span>
        )}
        {/* 단지로 가면 그 알림은 본 것이다. 화면을 떠나도 처리는 계속된다 (hook의 onSuccess는 화면과 무관하게 돈다) */}
        <Link
          to={`/complexes/${item.aptSeq}?area=${item.areaGroup}`}
          onClick={markIfUnread}
          className="font-medium text-slate-900 underline-offset-2 hover:underline"
        >
          {item.complexName}
        </Link>
        <span className="rounded bg-red-50 px-1.5 py-0.5 text-xs font-medium text-red-700">2020년 이후 최고가</span>
      </div>
      <p className="text-slate-900">
        <span className="text-lg font-semibold tabular-nums">{formatManwon(item.dealAmount)}</span>
        <span className="ml-2 text-sm text-slate-500">이전 최고 {formatManwon(item.previousMaxAmount)}</span>
      </p>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-slate-600">
          {formatArea(item.exclusiveArea)} · {item.floor}층 · 계약 {formatDate(item.dealDate)}
        </p>
        {!item.read && (
          <button
            type="button"
            onClick={markIfUnread}
            disabled={markRead.isPending}
            aria-label={`${item.complexName} 알림 읽음`}
            className="rounded-md border border-slate-300 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            읽음
          </button>
        )}
      </div>
      {markRead.isError && (
        <p role="alert" className="text-xs text-red-600">
          읽음 처리하지 못했어요. 잠시 후 다시 시도해 주세요.
        </p>
      )}
    </li>
  )
}
