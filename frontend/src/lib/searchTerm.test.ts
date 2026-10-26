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
    ['e편한세상', 'e편한세상'],
    ['SK', 'SK'],
    ['1', '1'],
  ])('%j → %j', (input, expected) => {
    expect(searchTerm(input)).toBe(expected)
  })

  it.each([
    ['헬리ㅇ', '헬리'],
    ['롯ㄷ', '롯'],
    ['롯데ㅋㅐ', '롯데'],
    ['래미안 ㄷ', '래미안'],
    ['롯ㄷ ', '롯'],
  ])('끝에 붙은 자모(조합 중)는 뗀다: %j → %j', (input, expected) => {
    expect(searchTerm(input)).toBe(expected)
  })
})
