import { describe, expect, it } from 'vitest'
import { DEFAULT_CENTER, DEFAULT_RADIUS, isCanonical, mapPath, mapSearchParams, parseMapParams } from './mapParams'

const parse = (search: string) => parseMapParams(new URLSearchParams(search))
const canonical = (search: string) => isCanonical(new URLSearchParams(search))

describe('parseMapParams', () => {
  it('범위 안의 위경도와 선택지 반경을 그대로 쓴다', () => {
    expect(parse('lat=37.499077&lng=127.112296&r=3000')).toEqual({ lat: 37.499077, lng: 127.112296, radius: 3000 })
  })

  it('위경도가 없거나 하나라도 잘못되면 둘 다 기본 중심(서울시청)', () => {
    const center = { lat: DEFAULT_CENTER.lat, lng: DEFAULT_CENTER.lng }
    for (const search of ['', 'lat=37.5', 'lng=127', 'lat=abc&lng=127', 'lat=&lng=127', 'lat=32.9&lng=127',
      'lat=39.1&lng=127', 'lat=37.5&lng=123.9', 'lat=37.5&lng=132.1', 'lat=Infinity&lng=127']) {
      expect(parse(search), search).toMatchObject(center)
    }
  })

  it('범위 경계는 들어간다 (서버와 같은 33–39, 124–132)', () => {
    expect(parse('lat=33&lng=132')).toMatchObject({ lat: 33, lng: 132 })
    expect(parse('lat=39&lng=124')).toMatchObject({ lat: 39, lng: 124 })
  })

  it('반경이 선택지가 아니면 기본 1km', () => {
    for (const search of ['', 'r=1500', 'r=0', 'r=abc', 'r=5000']) {
      expect(parse(search).radius, search).toBe(DEFAULT_RADIUS)
    }
    expect(parse('r=500').radius).toBe(500)
  })

  it('위경도는 소수 6자리로 반올림한다', () => {
    expect(parse('lat=37.4990774999&lng=127.1122956001')).toMatchObject({ lat: 37.499077, lng: 127.112296 })
  })
})

describe('isCanonical', () => {
  it('세 값이 모두 보정된 모양이어야 한다', () => {
    expect(canonical('lat=37.499077&lng=127.112296&r=1000')).toBe(true)
    expect(canonical('')).toBe(false)
    expect(canonical('lat=37.499077&lng=127.112296')).toBe(false)
    expect(canonical('lat=37.4990774999&lng=127.112296&r=1000')).toBe(false)
    expect(canonical('lat=37.499077&lng=127.112296&r=1500')).toBe(false)
  })
})

describe('mapSearchParams·mapPath', () => {
  it('세 값을 모두 쓴다', () => {
    expect(mapSearchParams({ lat: 37.5665, lng: 126.978, radius: 1000 }).toString()).toBe(
      'lat=37.5665&lng=126.978&r=1000',
    )
    expect(mapPath({ lat: 37.4990774999, lng: 127.112296, radius: 500 })).toBe(
      '/map?lat=37.499077&lng=127.112296&r=500',
    )
  })
})
