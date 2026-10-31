/** 반경 선택지 (m). 서버 최대는 3000m (ComplexController.nearby) */
export const RADII = [500, 1000, 2000, 3000] as const
export type Radius = (typeof RADII)[number]
export const DEFAULT_RADIUS: Radius = 1000

/** 파라미터가 없거나 잘못됐을 때의 중심: 서울시청 */
export const DEFAULT_CENTER = { lat: 37.5665, lng: 126.978 } as const

// 서버 검증 범위와 같다 (한국)
const LAT_RANGE = [33, 39] as const
const LNG_RANGE = [124, 132] as const

export type MapState = { lat: number; lng: number; radius: Radius }

/** 서버가 받는 위경도 범위 안인가. 지도 클릭이 이 밖이면 화면이 무시한다 (URL에 쓰면 기본 중심으로 보정돼 엉뚱한 곳으로 튄다) */
export function isInServiceArea(lat: number, lng: number): boolean {
  return inRange(lat, LAT_RANGE) && inRange(lng, LNG_RANGE)
}

/** URL에는 소수 6자리(약 0.1m)까지만 쓴다 */
export function roundCoord(value: number): number {
  return Math.round(value * 1e6) / 1e6
}

/**
 * ?lat=&lng=&r= 를 읽는다. 위경도는 둘 다 범위 안의 숫자여야 쓰고, 아니면 둘 다 기본 중심.
 * 반경은 선택지 중 하나가 아니면 기본 반경.
 */
export function parseMapParams(params: URLSearchParams): MapState {
  const lat = parseCoord(params.get('lat'), LAT_RANGE)
  const lng = parseCoord(params.get('lng'), LNG_RANGE)
  const center = lat !== null && lng !== null ? { lat, lng } : DEFAULT_CENTER
  const r = Number(params.get('r'))
  const radius = RADII.find((value) => value === r) ?? DEFAULT_RADIUS
  return { lat: roundCoord(center.lat), lng: roundCoord(center.lng), radius }
}

/** 지도 화면 주소의 쿼리. 항상 세 값을 모두 쓴다 (공유한 주소가 같은 화면을 보여준다) */
export function mapSearchParams(state: MapState): URLSearchParams {
  return new URLSearchParams({
    lat: String(roundCoord(state.lat)),
    lng: String(roundCoord(state.lng)),
    r: String(state.radius),
  })
}

/** 지금 주소가 이미 보정된 모양인가. 아니면 화면이 replace로 고친다 */
export function isCanonical(params: URLSearchParams): boolean {
  const canonical = mapSearchParams(parseMapParams(params))
  return ['lat', 'lng', 'r'].every((key) => params.get(key) === canonical.get(key))
}

/** 지도 화면 경로. 단지 상세의 "주변 단지 지도" 링크 등에 쓴다 */
export function mapPath(state: MapState): string {
  return `/map?${mapSearchParams(state).toString()}`
}

function parseCoord(raw: string | null, [min, max]: readonly [number, number]): number | null {
  if (raw === null || raw.trim() === '') return null
  const value = Number(raw)
  return inRange(value, [min, max]) ? value : null
}

function inRange(value: number, [min, max]: readonly [number, number]): boolean {
  return Number.isFinite(value) && value >= min && value <= max
}
