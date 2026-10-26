import { describe, expect, it } from 'vitest'
import { pickAreaGroup, parsePage } from './complexParams'

const group = (areaGroup: number, tradeCount: number, bulkCount = 0) => ({ areaGroup, tradeCount, bulkCount })

describe('pickAreaGroup', () => {
  const groups = [group(59, 120), group(85, 289), group(115, 40)]

  it('단지에 있는 그룹이면 그대로 쓴다 (거래 수가 적어도)', () => {
    expect(pickAreaGroup(groups, '115')).toBe(115)
  })

  it.each([null, '', 'abc', '84', '85.0', '085'])('%s 이면 거래 수 최다 그룹', (raw) => {
    expect(pickAreaGroup(groups, raw)).toBe(85)
  })

  it('거래 수가 같으면 일괄 거래가 많은 쪽, 그것도 같으면 작은 면적', () => {
    expect(pickAreaGroup([group(59, 0, 3), group(85, 0, 12)], null)).toBe(85)
    expect(pickAreaGroup([group(85, 10), group(59, 10)], null)).toBe(59)
  })

  it('그룹이 없으면 null', () => {
    expect(pickAreaGroup([], '85')).toBeNull()
  })
})

describe('parsePage', () => {
  it('1 이상의 정수는 그대로', () => {
    expect(parsePage('1')).toBe(1)
    expect(parsePage('12')).toBe(12)
  })

  it.each([null, '', '0', '-1', '1.5', 'abc', '2a'])('%s 이면 1', (raw) => {
    expect(parsePage(raw)).toBe(1)
  })
})
