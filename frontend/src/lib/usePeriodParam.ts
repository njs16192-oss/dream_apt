import { useCallback } from 'react'
import { useSearchParams } from 'react-router'
import { DEFAULT_PERIOD, parsePeriod, type Period } from './month'

/**
 * 차트 기간 ?period=. 기본(3년)이면 주소에서 뺀다.
 * 바꿀 때는 replace: 화면 안의 보기 설정이라 history를 쌓지 않는다. 다른 파라미터(area·page)는 그대로 둔다.
 */
export function usePeriodParam(): [Period, (next: Period) => void] {
  const [params, setParams] = useSearchParams()
  const period = parsePeriod(params.get('period'))
  const setPeriod = useCallback(
    (next: Period) =>
      setParams(
        (prev) => {
          const updated = new URLSearchParams(prev)
          if (next === DEFAULT_PERIOD) updated.delete('period')
          else updated.set('period', next)
          return updated
        },
        { replace: true },
      ),
    [setParams],
  )
  return [period, setPeriod]
}
