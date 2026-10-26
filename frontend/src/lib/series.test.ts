import { describe, expect, it } from 'vitest'
import { fillMonths } from './month'
import { formatEokAxis, isolatedMonths, monthTicks, priceTicks, seoulMonth, splitProvisional } from './series'

type Item = { month: string; v: number }
const item = (month: string, v: number): Item => ({ month, v })
const split = (items: Item[], from: string, to: string, provisionalFrom: string) =>
  splitProvisional(fillMonths(items, from, to), provisionalFrom, (i) => i.v).map(({ month, confirmed, provisional }) => [
    month,
    confirmed,
    provisional,
  ])

describe('splitProvisional', () => {
  it('집계 중 달은 점선에만, 경계 달(마지막 확정 달)은 두 선에 모두 넣는다', () => {
    const items = [item('2026-07', 1), item('2026-08', 2), item('2026-09', 3), item('2026-10', 4)]
    expect(split(items, '2026-07', '2026-10', '2026-09')).toEqual([
      ['2026-07', 1, null],
      ['2026-08', 2, 2],
      ['2026-09', null, 3],
      ['2026-10', null, 4],
    ])
  })

  it('경계 달이 빈 달이면 두 선이 떨어진다', () => {
    const items = [item('2026-07', 1), item('2026-09', 3), item('2026-10', 4)]
    expect(split(items, '2026-07', '2026-10', '2026-09')).toEqual([
      ['2026-07', 1, null],
      ['2026-08', null, null],
      ['2026-09', null, 3],
      ['2026-10', null, 4],
    ])
  })

  it('집계 중 달이 범위 밖이면 점선은 경계 달 하나뿐이다', () => {
    const items = [item('2025-01', 1), item('2025-02', 2)]
    expect(split(items, '2025-01', '2025-02', '2026-09')).toEqual([
      ['2025-01', 1, null],
      ['2025-02', 2, null],
    ])
  })
})

describe('isolatedMonths', () => {
  const rows = (months: [string, boolean][]) => months.map(([month, has]) => ({ month, item: has ? {} : null }))

  it('앞뒤가 비어 있는 달만 고르고, 범위 끝은 한쪽만 보면 된다', () => {
    const result = isolatedMonths(
      rows([
        ['2026-01', true],
        ['2026-02', false],
        ['2026-03', true],
        ['2026-04', true],
        ['2026-05', false],
        ['2026-06', true],
        ['2026-07', false],
      ]),
      '2026-09',
    )
    expect([...result.keys()]).toEqual(['2026-01', '2026-06'])
  })

  it('고립된 달이 집계 중이면 provisional로 표시한다', () => {
    const result = isolatedMonths(
      rows([
        ['2026-07', true],
        ['2026-08', false],
        ['2026-09', false],
        ['2026-10', true],
      ]),
      '2026-09',
    )
    expect(result.get('2026-07')).toEqual({ provisional: false })
    expect(result.get('2026-10')).toEqual({ provisional: true })
  })
})

describe('formatEokAxis', () => {
  it.each([
    [245000, '24.5억'],
    [90000, '9억'],
    [30500, '3.05억'],
    [10000, '1억'],
    [9000, '9,000만'],
  ])('%d → %s', (value, expected) => {
    expect(formatEokAxis(value)).toBe(expected)
  })
})

describe('monthTicks', () => {
  it('1년은 분기 첫 달마다 "yy.MM"', () => {
    const { ticks, format } = monthTicks('2025-11', '2026-10')
    expect(ticks).toEqual(['2026-01', '2026-04', '2026-07', '2026-10'])
    expect(format('2026-04')).toBe('26.04')
  })

  it('3년 이상은 매년 1월 연도', () => {
    const { ticks, format } = monthTicks('2023-11', '2026-10')
    expect(ticks).toEqual(['2024-01', '2025-01', '2026-01'])
    expect(format('2025-01')).toBe('2025')
  })

  it('6개월 이하는 매달', () => {
    expect(monthTicks('2026-08', '2026-10').ticks).toEqual(['2026-08', '2026-09', '2026-10'])
  })
})

describe('seoulMonth', () => {
  it('UTC 15:00(서울 자정)에 달이 바뀐다', () => {
    expect(seoulMonth(new Date('2026-10-31T14:59:59Z'))).toBe('2026-10')
    expect(seoulMonth(new Date('2026-10-31T15:00:00Z'))).toBe('2026-11')
  })
})

describe('priceTicks', () => {
  it('데이터 범위를 덮는 깔끔한 간격으로 나누고 0에서 시작하지 않는다', () => {
    expect(priceTicks([279500, 305500])).toEqual([270000, 280000, 290000, 300000, 310000])
    expect(priceTicks([190000, 310000])).toEqual([150000, 200000, 250000, 300000, 350000])
  })

  it('경계에 딱 맞으면 넘치지 않는다', () => {
    expect(priceTicks([100000, 140000])).toEqual([100000, 110000, 120000, 130000, 140000])
  })

  it('값이 하나뿐이면 위아래로 띄운다', () => {
    const ticks = priceTicks([245000])
    expect(ticks[0]).toBeLessThan(245000)
    expect(ticks.at(-1)).toBeGreaterThan(245000)
  })

  it('작은 금액도 1만원보다 잘게 나누지 않는다', () => {
    expect(priceTicks([9000, 9002]).every(Number.isInteger)).toBe(true)
  })
})
