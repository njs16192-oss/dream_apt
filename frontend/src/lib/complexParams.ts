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
