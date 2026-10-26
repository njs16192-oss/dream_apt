import { useEffect } from 'react'
import { useLocation } from 'react-router'

/**
 * 다른 화면으로 가면(pathname이 바뀌면) 맨 위로 올린다. BrowserRouter는 스크롤을 건드리지 않아서,
 * 검색 결과를 내린 채 단지를 누르면 상세 화면이 중간부터 보인다.
 * ?area=·?page=처럼 쿼리만 바뀌면 같은 화면 안의 이동이라 그대로 둔다.
 * 한계: 뒤로 가기도 맨 위로 간다 (위치 복원은 data router의 ScrollRestoration이 필요).
 */
export function ScrollToTop() {
  const { pathname } = useLocation()
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [pathname])
  return null
}
