import { describe, expect, it } from 'vitest'
import { safeNext } from './safeNext'

describe('safeNext', () => {
  it.each([
    ['/', '/'],
    ['/map', '/map'],
    ['/complexes/11710-6898?area=84', '/complexes/11710-6898?area=84'],
    ['/regions/11710?period=3y#chart', '/regions/11710?period=3y#chart'],
  ])('사이트 안 경로 %j는 그대로', (next, expected) => {
    expect(safeNext(next)).toBe(expected)
  })

  it.each([
    [null],
    [undefined],
    [''],
    ['map'],
    ['https://evil.com'],
    ['javascript:alert(1)'],
    ['//evil.com'],
    ['//evil.com/map'],
    ['/\\evil.com'],
    ['/\t/evil.com'],
    ['/\n/evil.com'],
  ])('다른 사이트로 갈 수 있는 %j는 첫 화면', (next) => {
    expect(safeNext(next)).toBe('/')
  })

  it.each([['/login'], ['/login?next=/map'], ['/signup']])('로그인·가입 화면 %j로는 돌아가지 않는다', (next) => {
    expect(safeNext(next)).toBe('/')
  })
})
