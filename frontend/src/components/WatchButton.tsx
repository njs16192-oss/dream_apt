import { useQuery } from '@tanstack/react-query'
import { Link, useLocation } from 'react-router'
import { meQuery, watchlistQuery } from '../api/queries'
import { useAddToWatchlist, useRemoveFromWatchlist } from '../api/watchlist'
import { loginPath } from '../lib/safeNext'
import { watchErrorMessage } from '../lib/watchlist'

const BUTTON_CLASS =
  'inline-flex shrink-0 items-center gap-1 rounded-lg border px-3 py-1.5 text-sm font-medium disabled:opacity-50'

/**
 * 단지 상세 헤더의 ☆ 버튼.
 * - 로그인 확인 중: 같은 폭의 빈 자리 (응답이 와도 제목 줄이 흔들리지 않게)
 * - 비로그인: 로그인 화면 링크. 로그인한 뒤 이 단지로 돌아온다
 * - 로그인: 내 관심단지 목록(['me','watchlist'])에 이 단지가 있는지로 상태를 정한다.
 *   목록이 오기 전이나 처리 중에는 누를 수 없다 (상태를 모르는 채로 보내지 않게)
 * 켜짐/꺼짐은 aria-pressed로 알린다. 이름은 "관심단지"로 두고 바꾸지 않는다 (토글 버튼 이름이 바뀌면 상태를 두 번 읽힌다)
 */
export function WatchButton({ aptSeq }: { aptSeq: string }) {
  const me = useQuery(meQuery)
  const location = useLocation()
  const watchlist = useQuery({ ...watchlistQuery, enabled: Boolean(me.data) })
  const add = useAddToWatchlist(aptSeq)
  const remove = useRemoveFromWatchlist(aptSeq)

  if (me.data === undefined) return <span aria-hidden="true" className="inline-block h-8 w-24 shrink-0" />

  if (me.data === null) {
    return (
      <Link
        to={loginPath(location.pathname + location.search)}
        title="로그인하고 관심단지에 추가"
        className={`${BUTTON_CLASS} border-slate-300 bg-white text-slate-700 hover:bg-slate-50`}
      >
        <span aria-hidden="true">☆</span>
        관심단지
      </Link>
    )
  }

  const watched = watchlist.data?.some((item) => item.aptSeq === aptSeq) ?? false
  const pending = add.isPending || remove.isPending
  const error = add.error ?? remove.error

  function toggle() {
    // 직전 시도의 오류 문구를 지우고 반대쪽을 보낸다
    add.reset()
    remove.reset()
    if (watched) remove.mutate()
    else add.mutate()
  }

  return (
    <div className="flex shrink-0 flex-col items-end gap-1">
      <button
        type="button"
        aria-pressed={watched}
        onClick={toggle}
        disabled={watchlist.data === undefined || pending}
        title={watchlist.isError ? '관심단지 목록을 불러오지 못했어요' : undefined}
        className={`${BUTTON_CLASS} ${
          watched
            ? 'border-amber-300 bg-amber-50 text-amber-800 hover:bg-amber-100'
            : 'border-slate-300 bg-white text-slate-700 hover:bg-slate-50'
        }`}
      >
        <span aria-hidden="true">{watched ? '★' : '☆'}</span>
        관심단지
      </button>
      {error && (
        <p role="alert" className="text-xs text-red-600">
          {watchErrorMessage(error)}
        </p>
      )}
    </div>
  )
}
