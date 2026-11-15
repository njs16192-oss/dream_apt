import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { meQuery } from '../api/queries'
import { loginPath } from '../lib/safeNext'
import { ErrorState } from './ErrorState'
import { Loading } from './Loading'

/**
 * 로그인해야 볼 수 있는 화면을 감싼다.
 * - 확인 중: 로딩. 확인 전에 children을 그리면 회원 API를 비로그인 상태로 부르게 된다
 * - 비로그인: 로그인 화면으로 replace. 로그인한 뒤 돌아오도록 ?next=에 지금 주소를 싣는다
 *   보던 중 세션이 끝나도(회원 API 401 → ['me'] = null) 같은 길로 로그인 화면에 간다
 * - 확인 실패(5xx 등): 로그인 여부를 모르므로 보내지 않고 오류를 보여준다
 */
export function RequireLogin({ children }: { children: ReactNode }) {
  const me = useQuery(meQuery)
  const location = useLocation()

  if (me.data === null) return <Navigate to={loginPath(location.pathname + location.search)} replace />
  if (me.data === undefined) {
    if (me.isError) return <ErrorState error={me.error} onRetry={() => me.refetch()} />
    return <Loading />
  }
  return children
}
