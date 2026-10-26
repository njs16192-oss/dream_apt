import { describe, expect, it } from 'vitest'
import { addMonths, chartRange, fillMonths, monthOf, parsePeriod } from './month'

describe('addMonths', () => {
  it('연도 경계를 넘는다', () => {
    expect(addMonths('2026-01', -1)).toBe('2025-12')
    expect(addMonths('2025-12', 1)).toBe('2026-01')
    expect(addMonths('2026-10', -119)).toBe('2016-11')
  })

  it('형식이 다르면 오류', () => {
    expect(() => addMonths('2026-1', 1)).toThrow()
  })
})

describe('monthOf', () => {
  it('날짜에서 달을 뗀다', () => {
    expect(monthOf('2026-10-25')).toBe('2026-10')
  })
})

describe('fillMonths', () => {
  const at = (month: string, count: number) => ({ month, count })

  it('응답에 없는 달을 null로 채운다 (연도 경계 포함)', () => {
    const filled = fillMonths([at('2025-11', 3), at('2026-02', 1)], '2025-11', '2026-02')

    expect(filled).toEqual([
      { month: '2025-11', item: at('2025-11', 3) },
      { month: '2025-12', item: null },
      { month: '2026-01', item: null },
      { month: '2026-02', item: at('2026-02', 1) },
    ])
  })

  it('앞뒤의 빈 달도 채운다', () => {
    const filled = fillMonths([at('2026-03', 2)], '2026-01', '2026-05')

    expect(filled.map((m) => m.item?.count ?? null)).toEqual([null, null, 2, null, null])
  })

  it('입력 순서와 상관없이 달 순서로 놓는다', () => {
    const filled = fillMonths([at('2026-03', 3), at('2026-01', 1), at('2026-02', 2)], '2026-01', '2026-03')

    expect(filled.map((m) => m.item?.count)).toEqual([1, 2, 3])
  })

  it('같은 달이 여러 번 오면 마지막 것을 쓴다', () => {
    const filled = fillMonths([at('2026-01', 1), at('2026-01', 9)], '2026-01', '2026-01')

    expect(filled).toEqual([{ month: '2026-01', item: at('2026-01', 9) }])
  })

  it('범위 밖 항목은 버린다', () => {
    const filled = fillMonths([at('2025-12', 1), at('2026-01', 2), at('2026-02', 3)], '2026-01', '2026-01')

    expect(filled).toEqual([{ month: '2026-01', item: at('2026-01', 2) }])
  })

  it('빈 배열이면 모든 달이 null', () => {
    expect(fillMonths([], '2026-01', '2026-03').map((m) => m.item)).toEqual([null, null, null])
  })

  it('from이 to보다 늦으면 빈 배열', () => {
    expect(fillMonths([], '2026-03', '2026-01')).toEqual([])
  })
})

describe('chartRange', () => {
  const items = [{ month: '2021-03' }, { month: '2020-01' }, { month: '2026-08' }]

  it('1년/3년/5년은 기준 달까지 그 개월 수', () => {
    expect(chartRange('1y', '2026-10-25', items)).toEqual({ from: '2025-11', to: '2026-10' })
    expect(chartRange('3y', '2026-10-25', items)).toEqual({ from: '2023-11', to: '2026-10' })
    expect(chartRange('5y', '2026-10-25', items)).toEqual({ from: '2021-11', to: '2026-10' })
  })

  it('전체는 첫 데이터 달부터 기준일이 속한 달까지 (응답 from과 무관)', () => {
    expect(chartRange('all', '2026-10-25', items)).toEqual({ from: '2020-01', to: '2026-10' })
  })

  it('전체인데 데이터가 없으면 기준 달 하나', () => {
    expect(chartRange('all', '2026-10-25', [])).toEqual({ from: '2026-10', to: '2026-10' })
  })
})

describe('parsePeriod', () => {
  it.each(['1y', '3y', '5y', 'all'])('%s는 그대로', (raw) => {
    expect(parsePeriod(raw)).toBe(raw)
  })

  it.each([null, '', '2y', 'ALL'])('%s 이면 기본 3년', (raw) => {
    expect(parsePeriod(raw)).toBe('3y')
  })
})
