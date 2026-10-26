import type { Schemas } from '../api/client'

type AreaGroupCounts = Pick<Schemas['AreaGroupSummary'], 'areaGroup' | 'tradeCount' | 'bulkCount'>

/**
 * URL의 ?area= 값이 단지에 있는 면적 그룹이면 그대로, 아니면(없음·숫자 아님·없는 그룹) 기본 그룹.
 * 기본 그룹: 거래 수 최다 → 같으면 일괄 거래 수가 많은 쪽 → 같으면 작은 면적. 그룹이 없으면 null.
 */
export function pickAreaGroup(groups: readonly AreaGroupCounts[], raw: string | null): number | null {
  const requested = groups.find((group) => String(group.areaGroup) === raw)
  if (requested) return requested.areaGroup
  let best: AreaGroupCounts | null = null
  for (const group of groups) {
    if (
      best === null ||
      group.tradeCount > best.tradeCount ||
      (group.tradeCount === best.tradeCount &&
        (group.bulkCount > best.bulkCount ||
          (group.bulkCount === best.bulkCount && group.areaGroup < best.areaGroup)))
    ) {
      best = group
    }
  }
  return best?.areaGroup ?? null
}

/** URL의 ?page= (1부터, 사람이 보는 값). 숫자가 아니거나 1 미만이면 1. API에는 1을 빼서 보낸다. */
export function parsePage(raw: string | null): number {
  if (raw === null || !/^\d+$/.test(raw)) return 1
  const page = Number(raw)
  return page >= 1 ? page : 1
}

/**
 * 현재 쿼리에 바꿀 값만 덮어쓴 search 문자열. null이면 지운다.
 * 탭·페이지 링크가 ?period= 같은 다른 보기 설정을 잃지 않게 한다.
 */
export function searchWith(params: URLSearchParams, changes: Record<string, string | null>): string {
  const updated = new URLSearchParams(params)
  for (const [key, value] of Object.entries(changes)) {
    if (value === null) updated.delete(key)
    else updated.set(key, value)
  }
  const search = updated.toString()
  return search ? `?${search}` : ''
}
