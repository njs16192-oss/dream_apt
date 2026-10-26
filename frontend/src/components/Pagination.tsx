import { Link } from 'react-router'

type Props = {
  /** 1부터 */
  page: number
  totalPages: number
  /** 해당 페이지로 가는 주소의 search. 예: n => `?area=85&page=${n}` */
  searchFor: (page: number) => string
}

/** 이전 / "3 / 68" / 다음. 링크라서 push (뒤로 가기로 이전 페이지). 한 페이지뿐이면 숨긴다 */
export function Pagination({ page, totalPages, searchFor }: Props) {
  if (totalPages <= 1) return null
  return (
    <nav aria-label="페이지" className="flex items-center justify-center gap-3 pt-2 text-sm">
      <PageLink label="이전" target={page > 1 ? searchFor(page - 1) : null} />
      <span className="text-slate-600 tabular-nums">
        {page} / {totalPages}
      </span>
      <PageLink label="다음" target={page < totalPages ? searchFor(page + 1) : null} />
    </nav>
  )
}

function PageLink({ label, target }: { label: string; target: string | null }) {
  const base = 'rounded-lg border px-3 py-1.5 font-medium'
  if (target === null) {
    return (
      <span aria-disabled="true" className={`${base} border-slate-200 text-slate-300`}>
        {label}
      </span>
    )
  }
  return (
    <Link to={{ search: target }} className={`${base} border-slate-300 bg-white text-slate-700 hover:bg-slate-50`}>
      {label}
    </Link>
  )
}
