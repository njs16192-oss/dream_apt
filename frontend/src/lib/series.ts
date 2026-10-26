import { addMonths, compareMonths, type FilledMonth, type YearMonth } from './month'

export type SplitRow<T> = {
  month: YearMonth
  item: T | null
  /** 확정 구간 선의 값. 집계 중 달이면 null */
  confirmed: number | null
  /** 집계 중 구간 선의 값. 경계 달(마지막 확정 달)도 넣어서 두 선이 이어 보이게 한다 */
  provisional: number | null
}

/**
 * 선 하나의 일부만 점선으로 그릴 수 없어서 확정(실선)·집계 중(점선) 두 선으로 나눈다 (ADR 008 ③).
 * 경계 달이 빈 달(null)이면 공유할 점이 없어 두 선이 떨어진다. 빈 달에서 선을 끊는 규칙과 같은 결과다.
 */
export function splitProvisional<T>(
  filled: readonly FilledMonth<T>[],
  provisionalFrom: YearMonth,
  value: (item: T) => number,
): SplitRow<T>[] {
  const boundary = addMonths(provisionalFrom, -1)
  return filled.map(({ month, item }) => {
    const v = item === null ? null : value(item)
    const isProvisional = compareMonths(month, provisionalFrom) >= 0
    return {
      month,
      item,
      confirmed: isProvisional ? null : v,
      provisional: isProvisional || month === boundary ? v : null,
    }
  })
}

/**
 * 앞뒤 달이 모두 비어 있는(범위 끝이면 한쪽만) 값 있는 달. connectNulls={false}에서는 이런 달에 선이 그려지지 않아
 * 점으로 따로 표시해야 한다. 집계 중 달이면 점도 흐리게 그리도록 provisional을 담는다.
 */
export function isolatedMonths(
  rows: readonly { month: YearMonth; item: unknown }[],
  provisionalFrom: YearMonth,
): Map<YearMonth, { provisional: boolean }> {
  const result = new Map<YearMonth, { provisional: boolean }>()
  rows.forEach((row, index) => {
    if (row.item === null) return
    const prev = rows[index - 1]
    const next = rows[index + 1]
    if ((prev === undefined || prev.item === null) && (next === undefined || next.item === null)) {
      result.set(row.month, { provisional: compareMonths(row.month, provisionalFrom) >= 0 })
    }
  })
  return result
}

const eok = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 })
const man = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 })

/** 가격 y축 눈금 (만원). 245000 → "24.5억", 90000 → "9억", 9000 → "9,000만" */
export function formatEokAxis(manwon: number): string {
  return manwon >= 10000 ? `${eok.format(manwon / 10000)}억` : `${man.format(manwon)}만`
}

/**
 * x축 눈금 달과 표기. 기간 길이에 따라 간격을 바꾼다: 연도 눈금만 쓰면 1년 보기에 눈금이 1–2개뿐이다.
 * - 6개월 이하: 매달 "26.04"
 * - 24개월 이하: 분기 첫 달(1·4·7·10월) "26.04"
 * - 그보다 길면: 매년 1월 "2024"
 */
export function monthTicks(from: YearMonth, to: YearMonth): { ticks: YearMonth[]; format: (month: YearMonth) => string } {
  const span = compareMonths(to, from) + 1
  const yearly = span > 24
  const step = span <= 6 ? 1 : 3
  const ticks: YearMonth[] = []
  for (let month = from; compareMonths(month, to) <= 0; month = addMonths(month, 1)) {
    const monthNumber = Number(month.slice(5))
    if (yearly ? monthNumber === 1 : (monthNumber - 1) % step === 0) ticks.push(month)
  }
  return {
    ticks,
    format: yearly ? (month) => month.slice(0, 4) : (month) => `${month.slice(2, 4)}.${month.slice(5)}`,
  }
}

const seoulMonthFormat = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit' })

/** 서울 기준 이번 달 "2026-10". 사용자 PC 시계를 따른다 */
export function seoulMonth(now: Date = new Date()): YearMonth {
  return seoulMonthFormat.format(now)
}
