/** "yyyy-MM" 형식의 달. API의 month·provisionalFrom과 같은 형식이다. */
export type YearMonth = string

const PATTERN = /^(\d{4})-(\d{2})$/

function toIndex(month: YearMonth): number {
  const match = PATTERN.exec(month)
  if (!match) throw new Error(`yyyy-MM 형식이 아닙니다: ${month}`)
  return Number(match[1]) * 12 + Number(match[2]) - 1
}

function fromIndex(index: number): YearMonth {
  const year = Math.floor(index / 12)
  const month = (index % 12) + 1
  return `${year}-${String(month).padStart(2, '0')}`
}

export function addMonths(month: YearMonth, amount: number): YearMonth {
  return fromIndex(toIndex(month) + amount)
}

/** "2026-10-25" → "2026-10" */
export function monthOf(date: string): YearMonth {
  return date.slice(0, 7)
}

export function compareMonths(a: YearMonth, b: YearMonth): number {
  return toIndex(a) - toIndex(b)
}

export type FilledMonth<T> = { month: YearMonth; item: T | null }

/**
 * from부터 to까지(둘 다 포함) 모든 달을 만들고, 응답에 없는 달은 item = null로 채운다.
 * 입력 순서는 상관없다. 범위 밖 항목은 버리고, 같은 달이 여러 번 오면 마지막 것을 쓴다.
 */
export function fillMonths<T extends { month: YearMonth }>(
  items: readonly T[],
  from: YearMonth,
  to: YearMonth,
): FilledMonth<T>[] {
  const byMonth = new Map(items.map((item) => [item.month, item]))
  const result: FilledMonth<T>[] = []
  for (let index = toIndex(from); index <= toIndex(to); index++) {
    const month = fromIndex(index)
    result.push({ month, item: byMonth.get(month) ?? null })
  }
  return result
}

export type Period = '1y' | '3y' | '5y' | 'all'

export const PERIODS: readonly { value: Period; label: string; months?: number }[] = [
  { value: '1y', label: '1년', months: 12 },
  { value: '3y', label: '3년', months: 36 },
  { value: '5y', label: '5년', months: 60 },
  { value: 'all', label: '전체' },
]

/**
 * 차트에 그릴 달 범위 (ADR 008 ③). 끝은 기준일이 속한 달.
 * 시작은 1년/3년/5년이면 끝에서 거꾸로 센 첫 달, 전체면 첫 데이터 달
 * (응답의 from은 null이거나 데이터보다 이를 수 있다). 데이터가 없으면 기준 달 하나.
 */
export function chartRange(
  period: Period,
  baseDate: string,
  items: readonly { month: YearMonth }[],
): { from: YearMonth; to: YearMonth } {
  const to = monthOf(baseDate)
  const months = PERIODS.find((p) => p.value === period)?.months
  if (months !== undefined) return { from: addMonths(to, -(months - 1)), to }
  const first = items.reduce<YearMonth | null>(
    (min, { month }) => (min === null || compareMonths(month, min) < 0 ? month : min),
    null,
  )
  return { from: first !== null && compareMonths(first, to) < 0 ? first : to, to }
}
