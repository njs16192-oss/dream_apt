import { describe, expect, it } from 'vitest'
import { formatArea, formatDate, formatManwon, toPyeong } from './format'

describe('formatManwon', () => {
  it.each([
    [245000, '24억 5,000만'],
    [9999, '9,999만'],
    [10000, '1억'],
    [10001, '1억 1만'],
    [19999, '1억 9,999만'],
    [100000, '10억'],
    [1000000, '100억'],
    [12345678, '1,234억 5,678만'],
    [500, '500만'],
    [0, '0만'],
  ])('%d만원 → %s', (amount, expected) => {
    expect(formatManwon(amount)).toBe(expected)
  })
})

describe('toPyeong', () => {
  it.each([
    [84.97, 25.7],
    [59.99, 18.1],
    [114.92, 34.8],
    [33.0578, 10],
  ])('%f㎡ → %f평', (m2, pyeong) => {
    expect(toPyeong(m2)).toBe(pyeong)
  })

  it('정확히 .x5평이 되는 면적은 올린다', () => {
    // ㎡ × 121/400이 정확히 소수 둘째 자리 5로 끝나는 면적
    expect(toPyeong(20)).toBe(6.1) // 6.05
    expect(toPyeong(60)).toBe(18.2) // 18.15
    expect(toPyeong(180)).toBe(54.5) // 54.45
  })

  it('.x5 바로 아래는 내린다', () => {
    expect(toPyeong(19.99)).toBe(6) // 6.0469...
  })
})

describe('formatArea', () => {
  it("'전용'을 숫자 앞에 붙이고 평은 소수 한 자리로 쓴다", () => {
    expect(formatArea(84.97)).toBe('전용 84.97㎡ (25.7평)')
    expect(formatArea(85)).toBe('전용 85㎡ (25.7평)')
    expect(formatArea(33.0578)).toBe('전용 33.0578㎡ (10.0평)')
  })
})

describe('formatDate', () => {
  it('하이픈을 점으로 바꾼다', () => {
    expect(formatDate('2026-09-18')).toBe('2026.09.18')
  })
})
