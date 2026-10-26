import { describe, expect, it } from 'vitest'
import { formatDong, formatFloor, registrationLabel } from './trade'

describe('registrationLabel', () => {
  it('등기일이 있으면 날짜', () => {
    expect(registrationLabel({ dealDate: '2021-03-02', registeredDate: '2021-05-10' })).toBe('2021.05.10')
  })

  it('등기일 필드가 생긴 2023-01 이후 계약인데 비어 있으면 등기 전', () => {
    expect(registrationLabel({ dealDate: '2023-01-01', registeredDate: null })).toBe('등기 전')
    expect(registrationLabel({ dealDate: '2026-10-20', registeredDate: null })).toBe('등기 전')
  })

  it('그 전 계약이 비어 있으면 알 수 없음(null)', () => {
    expect(registrationLabel({ dealDate: '2022-12-31', registeredDate: null })).toBeNull()
  })
})

describe('formatDong', () => {
  it.each([
    ['102', '102동'],
    ['103동', '103동'],
    ['에이', '에이동'],
  ])('%s → %s', (dong, expected) => {
    expect(formatDong(dong)).toBe(expected)
  })
})

describe('formatFloor', () => {
  it.each([
    [12, '12층'],
    [1, '1층'],
    [-1, '지하 1층'],
  ])('%d → %s', (floor, expected) => {
    expect(formatFloor(floor)).toBe(expected)
  })
})
