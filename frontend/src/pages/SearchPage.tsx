import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { complexSearchQuery, regionsQuery, SEARCH_LIMIT } from '../api/queries'
import { ComplexSearchResult } from '../components/ComplexSearchResult'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { Loading } from '../components/Loading'
import { searchTerm } from '../lib/searchTerm'
import { useDebouncedValue } from '../lib/useDebouncedValue'

/**
 * 검색 조건은 URL(?q=&sggCd=)이 기준이다. 입력창만 로컬 state로 두고 300ms 뒤 URL에 replace로 쓴다.
 * 뒤로 가기처럼 URL이 바깥에서 바뀌면 입력창을 URL 값으로 맞춘다.
 */
export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const urlQ = params.get('q') ?? ''
  const sggCd = params.get('sggCd') || null

  const [input, setInput] = useState(urlQ)
  const debouncedInput = useDebouncedValue(input, 300)
  // 마지막으로 이 화면이 URL에 쓴 q. URL의 q가 이것과 다르면 바깥에서 바뀐 것이다.
  // 비교 없이 URL → 입력을 따라가게 하면, 입력 → URL → 입력으로 되돌아오는 동안 사용자가 친 글자를 덮어쓴다.
  const lastWrittenQ = useRef(urlQ)

  useEffect(() => {
    if (urlQ !== lastWrittenQ.current) {
      lastWrittenQ.current = urlQ
      setInput(urlQ)
    }
  }, [urlQ])

  useEffect(() => {
    const next = debouncedInput.trim()
    if (next === lastWrittenQ.current) return
    lastWrittenQ.current = next
    setParams(
      (prev) => {
        const updated = new URLSearchParams(prev)
        if (next) updated.set('q', next)
        else updated.delete('q')
        return updated
      },
      { replace: true },
    )
  }, [debouncedInput, setParams])

  function changeRegion(value: string) {
    setParams(
      (prev) => {
        const updated = new URLSearchParams(prev)
        if (value) updated.set('sggCd', value)
        else updated.delete('sggCd')
        return updated
      },
      { replace: true },
    )
  }

  const regions = useQuery(regionsQuery)
  const selectedRegion = regions.data?.find((region) => region.sggCd === sggCd)

  const term = searchTerm(urlQ)
  const search = useQuery({ ...complexSearchQuery(term ?? '', sggCd), enabled: term !== null })
  // 입력을 지웠으면 URL 반영(300ms)을 기다리지 않고 바로 안내로 돌아간다. 이전 결과가 남아 보이지 않게
  const showGuide = term === null || input.trim() === ''

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold">단지 검색</h1>

      <form role="search" onSubmit={(event) => event.preventDefault()} className="flex flex-col gap-2 sm:flex-row">
        <label htmlFor="search-q" className="sr-only">
          단지명
        </label>
        <input
          id="search-q"
          type="search"
          value={input}
          onChange={(event) => setInput(event.target.value)}
          maxLength={50}
          placeholder="단지명 (예: 헬리오시티)"
          autoComplete="off"
          enterKeyHint="search"
          className="min-w-0 flex-1 rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-base outline-none focus:border-blue-600 focus:ring-2 focus:ring-blue-600/20"
        />
        <label htmlFor="search-sgg" className="sr-only">
          구
        </label>
        <select
          id="search-sgg"
          value={sggCd ?? ''}
          onChange={(event) => changeRegion(event.target.value)}
          disabled={!regions.data}
          className="rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-base disabled:text-slate-400 sm:w-40"
        >
          <option value="">{regions.isError ? '구 목록을 못 불러옴' : '전체 구'}</option>
          {regions.data?.map((region) => (
            <option key={region.sggCd} value={region.sggCd}>
              {region.sggName}
            </option>
          ))}
        </select>
      </form>

      {selectedRegion && (
        <Link
          to={`/regions/${selectedRegion.sggCd}`}
          className="inline-block text-sm font-medium text-blue-700 underline-offset-2 hover:underline"
        >
          {selectedRegion.sggName} 거래량 보기 →
        </Link>
      )}

      {showGuide ? (
        <EmptyState title="단지 이름으로 검색하세요">한 글자부터 검색할 수 있어요. 예: 헬리오시티, 래미안, 롯데캐슬</EmptyState>
      ) : search.isError ? (
        <ErrorState error={search.error} onRetry={() => search.refetch()} />
      ) : search.data === undefined ? (
        <Loading label="검색 중" />
      ) : search.data.length === 0 ? (
        <EmptyState title={`'${term}' 검색 결과가 없어요`}>
          {selectedRegion ? `${selectedRegion.sggName} 안에서만 찾았어요. 구 필터를 풀어 보세요.` : '단지명 일부만 입력해 보세요.'}
        </EmptyState>
      ) : (
        <section aria-label="검색 결과" className={search.isPlaceholderData ? 'opacity-50 transition-opacity' : undefined}>
          <p className="mb-2 text-xs text-slate-500">최근 12개월 거래 수는 일괄 거래(같은 단지·같은 날 10건 이상)를 뺀 수예요.</p>
          <ul className="space-y-2">
            {search.data.map((item) => (
              <li key={item.aptSeq}>
                <ComplexSearchResult item={item} />
              </li>
            ))}
          </ul>
          {search.data.length >= SEARCH_LIMIT && (
            <p className="mt-3 text-center text-sm text-slate-500">
              결과가 더 있을 수 있어요. 검색어를 더 구체적으로 입력해 보세요.
            </p>
          )}
        </section>
      )}
    </div>
  )
}
