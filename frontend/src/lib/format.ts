const number = new Intl.NumberFormat('ko-KR')

/**
 * 만원 단위 금액을 억·만으로 나눠 쓴다. 0인 부분은 생략한다.
 * 245000 → "24억 5,000만", 100000 → "10억", 9999 → "9,999만"
 */
export function formatManwon(amount: number): string {
  const eok = Math.floor(amount / 10000)
  const man = amount % 10000
  if (eok === 0) return `${number.format(man)}만`
  if (man === 0) return `${number.format(eok)}억`
  return `${number.format(eok)}억 ${number.format(man)}만`
}

/**
 * 전용면적(㎡)을 평으로 바꾼다. 1평 = 400/121㎡, 소수 첫째 자리 반올림.
 * 공급면적 기준인 "34평형"과 다르다 (ADR 008 ④).
 */
export function toPyeong(squareMeters: number): number {
  return Math.round((squareMeters * 121) / 40) / 10
}

/** "전용 84.97㎡ (25.7평)". '전용'을 숫자 앞에 붙여 공급면적 평형과 헷갈리지 않게 한다. */
export function formatArea(squareMeters: number): string {
  return `전용 ${squareMeters}㎡ (${toPyeong(squareMeters).toFixed(1)}평)`
}
