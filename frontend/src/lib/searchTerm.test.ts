import { describe, expect, it } from 'vitest'
import { searchTerm } from './searchTerm'

describe('searchTerm', () => {
  it.each(['', '   ', 'ㄹ', 'ㅎㅔ', 'ㄹ ', ' ㄹㅗ '])('%j는 요청하지 않는다', (input) => {
    expect(searchTerm(input)).toBeNull()
  })

  it.each([
    ['롯', '롯'],
    ['헬리오 ', '헬리오'],
    ['  래미안 대치 ', '래미안 대치'],
    ['헬리ㅇ', '헬리ㅇ'],
    ['e편한세상', 'e편한세상'],
    ['SK', 'SK'],
    ['1', '1'],
  ])('%j → %j', (input, expected) => {
    expect(searchTerm(input)).toBe(expected)
  })
})
