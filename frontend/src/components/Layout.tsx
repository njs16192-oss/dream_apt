import { Link, Outlet } from 'react-router'

export function Layout() {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-10 border-b border-slate-200 bg-white/90 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-4xl items-center px-4">
          <Link to="/" className="text-lg font-bold tracking-tight text-slate-900">
            Dream APT
          </Link>
        </div>
      </header>
      <main className="mx-auto w-full max-w-4xl flex-1 px-4 py-6">
        <Outlet />
      </main>
      <footer className="border-t border-slate-200 px-4 py-6 text-center text-xs text-slate-500">
        출처: 국토교통부 아파트 매매 실거래가 상세 자료 (서울). 해제된 거래는 제외합니다.
      </footer>
    </div>
  )
}
