/**
 * 차트 색. dataviz 기본 팔레트의 categorical slot 1·2 (라이트 모드).
 * validate_palette.js "#2a78d6,#eb6834" --mode light --surface #ffffff → 전 항목 통과 (CVD ΔE 24.7, 정상 시각 ΔE 33.6)
 * 앱이 라이트 전용이라 다크 단계는 아직 없다.
 */
export const CHART = {
  /** 가격선, 일반 거래 막대 */
  primary: '#2a78d6',
  /** 일괄 거래 막대 */
  bulk: '#eb6834',
  /** 집계 중 표시: 같은 색을 흐리게 */
  provisionalOpacity: 0.45,
  surface: '#ffffff',
  grid: '#e1e0d9',
  axis: '#c3c2b7',
  axisText: '#898781',
} as const
