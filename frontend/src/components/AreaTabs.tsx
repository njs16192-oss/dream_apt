import { useEffect, useRef } from 'react'
import { Link } from 'react-router'
import type { Schemas } from '../api/client'
import { formatArea, formatDate, formatManwon } from '../lib/format'

type Props = {
  groups: readonly Schemas['AreaGroupSummary'][]
  selected: number
}

/**
 * 면적 그룹 선택. 고르면 URL(?area=)이 바뀌는 이동이라 ARIA tabs가 아니라 링크로 둔다.
 * 면적을 바꾸면 페이지는 처음부터 (page를 지운다). push라서 뒤로 가기로 이전 면적에 돌아간다.
 */
export function AreaTabs({ groups, selected }: Props) {
  const navRef = useRef<HTMLElement>(null)

  // 탭이 많으면 선택된 탭(보통 거래 수 최다 그룹)이 가로 스크롤 밖에 있어 무엇을 보는지 모른다. 가운데로 가져온다.
  // scrollIntoView는 페이지까지 세로로 움직일 수 있어서 탭 줄의 scrollLeft만 바꾼다
  useEffect(() => {
    const nav = navRef.current
    const tab = nav?.querySelector('[aria-current="page"]')
    if (!nav || !tab) return
    const navRect = nav.getBoundingClientRect()
    const tabRect = tab.getBoundingClientRect()
    nav.scrollLeft += tabRect.left - navRect.left - (navRect.width - tabRect.width) / 2
  }, [selected])

  return (
    <div className="space-y-2">
      <p className="text-xs text-slate-500">전용면적을 ㎡ 단위로 반올림해 묶었어요. 거래 목록에는 실제 면적이 나와요.</p>
      <nav ref={navRef} aria-label="면적" className="-mx-4 overflow-x-auto px-4 pb-1">
        <ul className="flex gap-2">
          {groups.map((group) => {
            const isSelected = group.areaGroup === selected
            return (
              <li key={group.areaGroup} className="shrink-0">
                <Link
                  to={{ search: `?area=${group.areaGroup}` }}
                  aria-current={isSelected ? 'page' : undefined}
                  className={`block rounded-xl border px-3 py-2.5 text-left transition-colors ${
                    isSelected
                      ? 'border-blue-600 bg-blue-50 ring-1 ring-blue-600'
                      : 'border-slate-200 bg-white hover:border-slate-400'
                  }`}
                >
                  <AreaTabContent group={group} />
                </Link>
              </li>
            )
          })}
        </ul>
      </nav>
    </div>
  )
}

function AreaTabContent({ group }: { group: Schemas['AreaGroupSummary'] }) {
  return (
    <>
      <span className="block font-semibold whitespace-nowrap text-slate-900">{formatArea(group.areaGroup)}</span>
      <span className="block text-xs whitespace-nowrap text-slate-500">실면적 {exclusiveAreaRange(group.exclusiveAreas)}</span>
      <span className="mt-1 block text-sm whitespace-nowrap text-slate-700">
        {group.tradeCount.toLocaleString('ko-KR')}건
        {group.bulkCount > 0 && <span className="text-slate-500"> · 일괄 {group.bulkCount.toLocaleString('ko-KR')}건</span>}
      </span>
      <span className="block text-xs whitespace-nowrap text-slate-500">
        {group.lastDealAmount !== null && group.lastDealDate !== null
          ? `최근 ${formatManwon(group.lastDealAmount)} · ${formatDate(group.lastDealDate)}`
          : '일반 거래 없음'}
      </span>
    </>
  )
}

/** 오름차순 원값 목록 → "84.95–84.99㎡", 하나면 "84.97㎡" */
function exclusiveAreaRange(areas: readonly number[]): string {
  if (areas.length === 0) return '-'
  const first = areas[0]
  const last = areas[areas.length - 1]
  return first === last ? `${first}㎡` : `${first}–${last}㎡`
}
