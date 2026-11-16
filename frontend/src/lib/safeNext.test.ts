import { describe, expect, it } from 'vitest'
import { loginPath, safeNext } from './safeNext'

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

describe('loginPath', () => {
  it('지금 주소를 인코딩해 next에 싣는다', () => {
    expect(loginPath('/complexes/11710-1?area=85')).toBe('/login?next=%2Fcomplexes%2F11710-1%3Farea%3D85')
  })

  it('만든 next는 safeNext를 거쳐 원래 주소로 돌아온다', () => {
    const next = new URLSearchParams(loginPath('/watchlist?a=1&b=2').split('?')[1]).get('next')
    expect(safeNext(next)).toBe('/watchlist?a=1&b=2')
  })
})
