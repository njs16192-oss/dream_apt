import { useQuery } from '@tanstack/react-query'
import { flushSync } from 'react-dom'
import { Link, useLocation, useNavigate } from 'react-router'
import { useLogout } from '../api/auth'
import { meQuery } from '../api/queries'
import { loginPath } from '../lib/safeNext'

/** 이 화면들에서는 "로그인" 링크를 숨긴다 (이미 그 화면이다) */
const AUTH_PATHS = new Set(['/login', '/signup'])

/**
 * 헤더 오른쪽의 로그인 상태.
 * - 확인 중이거나 확인에 실패하면 빈 자리만 둔다. 헤더 때문에 전체 오류 화면을 띄우지 않는다
 * - 비로그인: "로그인" 링크. 로그인한 뒤 지금 화면으로 돌아오도록 ?next=에 현재 주소를 싣는다
 * - 로그인: 관심단지 링크 + 이메일 + 로그아웃 버튼
 * - 로그아웃 버튼으로 로그아웃하면 첫 화면('/')으로 간다. 로그인이 필요한 화면에 있었다면 거기서 벗어나야 하고,
 *   공개 화면에서도 같은 동작으로 맞춘다. 세션 만료(401)일 때는 RequireLogin이 로그인 화면(?next=)으로 보낸다
 */
export function AuthMenu() {
  const me = useQuery(meQuery)
  const navigate = useNavigate()
  // 이동을 화면에 다 반영한 뒤에 캐시를 비운다 (useLogout의 onLoggedOut). 반영 전에 me = null이 그려지면
  // 로그인이 필요한 화면의 RequireLogin이 로그인 화면으로 replace해 버린다.
  // flushSync가 이동을 바로 그리려면 라우터가 이동을 transition으로 미루지 않아야 한다 (main.tsx의 useTransitions={false})
  const logout = useLogout({ onLoggedOut: () => flushSync(() => navigate('/')) })
  const location = useLocation()

  if (me.data === undefined) {
    // 폭을 잡아 둬서 응답이 와도 헤더가 흔들리지 않게 한다
    return <span aria-hidden="true" className="inline-block w-14" />
  }

  if (me.data === null) {
    if (AUTH_PATHS.has(location.pathname)) return null
    return (
      <Link
        to={loginPath(location.pathname + location.search)}
        className="rounded-md px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-100"
      >
        로그인
      </Link>
    )
  }

  return (
    <div className="flex min-w-0 items-center gap-1">
      <Link
        to="/watchlist"
        className="shrink-0 rounded-md px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-100"
      >
        관심단지
      </Link>
      {/* 좁은 화면에서는 메뉴가 넘치지 않게 이메일을 숨긴다 */}
      <span title={me.data.email} className="hidden max-w-56 truncate text-sm text-slate-600 sm:block">
        {me.data.email}
      </span>
      <button
        type="button"
        onClick={() => logout.mutate()}
        disabled={logout.isPending}
        className="shrink-0 rounded-md px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-100 disabled:opacity-50"
      >
        {logout.isPending ? '로그아웃 중…' : '로그아웃'}
      </button>
      {logout.isError && (
        // 헤더가 좁아 짧게 쓰고, 자세한 문구는 title로
        <span role="alert" title="잠시 후 다시 시도해 주세요" className="shrink-0 text-xs text-red-600">
          로그아웃 실패
        </span>
      )}
    </div>
  )
}
