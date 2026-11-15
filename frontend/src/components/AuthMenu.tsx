import { useQuery } from '@tanstack/react-query'
import { Link, useLocation } from 'react-router'
import { useLogout } from '../api/auth'
import { meQuery } from '../api/queries'

/** 이 화면들에서는 "로그인" 링크를 숨긴다 (이미 그 화면이다) */
const AUTH_PATHS = new Set(['/login', '/signup'])

/**
 * 헤더 오른쪽의 로그인 상태.
 * - 확인 중이거나 확인에 실패하면 빈 자리만 둔다. 헤더 때문에 전체 오류 화면을 띄우지 않는다
 * - 비로그인: "로그인" 링크. 로그인한 뒤 지금 화면으로 돌아오도록 ?next=에 현재 주소를 싣는다
 * - 로그인: 이메일 + 로그아웃 버튼. 로그아웃해도 화면은 옮기지 않는다 (지금은 공개 화면뿐)
 */
export function AuthMenu() {
  const me = useQuery(meQuery)
  const logout = useLogout()
  const location = useLocation()

  if (me.data === undefined) {
    // 폭을 잡아 둬서 응답이 와도 헤더가 흔들리지 않게 한다
    return <span aria-hidden="true" className="inline-block w-14" />
  }

  if (me.data === null) {
    if (AUTH_PATHS.has(location.pathname)) return null
    const next = encodeURIComponent(location.pathname + location.search)
    return (
      <Link
        to={`/login?next=${next}`}
        className="rounded-md px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-100"
      >
        로그인
      </Link>
    )
  }

  return (
    <div className="flex min-w-0 items-center gap-1">
      <span title={me.data.email} className="max-w-32 truncate text-sm text-slate-600 sm:max-w-56">
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
