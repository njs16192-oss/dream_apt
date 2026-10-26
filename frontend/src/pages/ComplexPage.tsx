import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import type { Schemas } from '../api/client'
import { complexDetailQuery } from '../api/queries'
import { ErrorState } from '../components/ErrorState'
import { Loading } from '../components/Loading'
import { useDocumentTitle } from '../lib/useDocumentTitle'

export function ComplexPage() {
  const aptSeq = useParams().aptSeq!
  const detail = useQuery(complexDetailQuery(aptSeq))
  useDocumentTitle(detail.data?.name)

  if (detail.isError) {
    return <ErrorState error={detail.error} notFoundTitle="단지를 찾을 수 없어요" onRetry={() => detail.refetch()} />
  }
  if (detail.data === undefined) return <Loading />

  return (
    <div className="space-y-4">
      <ComplexHeader complex={detail.data} />
      {detail.data.bulkDominant && <BulkDominantNotice ratio={detail.data.bulkRatio} />}
    </div>
  )
}

function ComplexHeader({ complex }: { complex: Schemas['ComplexDetail'] }) {
  const location = [complex.sggName, complex.umdName].filter(Boolean).join(' ')
  return (
    <header className="space-y-1">
      <h1 className="text-2xl font-bold text-slate-900">{complex.name}</h1>
      <p className="text-sm text-slate-600">
        {location}
        {complex.buildYear !== null && ` · ${complex.buildYear}년 준공`}
      </p>
      {complex.roadAddress && <p className="text-sm text-slate-500">{complex.roadAddress}</p>}
      <Link
        to={`/regions/${complex.sggCd}`}
        className="inline-block pt-1 text-sm font-medium text-blue-700 underline-offset-2 hover:underline"
      >
        {complex.sggName} 거래량 보기 →
      </Link>
    </header>
  )
}

/** 일괄 거래 위주 단지 안내. C에서 가격 추이 대신 보여줄 안내와 같은 문구를 쓴다 */
function BulkDominantNotice({ ratio }: { ratio: number }) {
  return (
    <div role="note" className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
      거래의 {Math.round(ratio * 100)}%가 일괄 거래(같은 단지·같은 날 10건 이상)예요. 일반 시세와 다를 수 있어요.
    </div>
  )
}
