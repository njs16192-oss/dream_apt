import type { Schemas } from '../api/client'
import { formatDate } from './format'

type Trade = Schemas['TradeItem']

/**
 * 원본에 등기일 필드가 생긴 첫 계약일. 이보다 앞선 계약은 등기됐어도 등기일이 없다.
 * DB 확인 (2026-10): 2022-12 계약까지 registered_date가 100% null, 2023-01부터 0.1%
 */
export const REGISTERED_DATE_SINCE = '2023-01-01'

/** 등기일. 필드가 생기기 전 계약은 알 수 없음(null), 그 뒤 계약인데 비어 있으면 '등기 전' */
export function registrationLabel(trade: Pick<Trade, 'dealDate' | 'registeredDate'>): string | null {
  if (trade.registeredDate !== null) return formatDate(trade.registeredDate)
  return trade.dealDate < REGISTERED_DATE_SINCE ? null : '등기 전'
}

/** 동은 "102"처럼 숫자만 오는 게 대부분이지만 "103동"도 섞여 있다. 끝이 '동'이 아닐 때만 붙인다 */
export function formatDong(aptDong: string): string {
  return aptDong.endsWith('동') ? aptDong : `${aptDong}동`
}

/** 음수 층은 지하 */
export function formatFloor(floor: number): string {
  return floor < 0 ? `지하 ${-floor}층` : `${floor}층`
}
