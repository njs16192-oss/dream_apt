import { useEffect, useState } from 'react'

/** value가 delay 동안 바뀌지 않으면 그 값을 돌려준다. 그 전에는 마지막으로 확정된 값. */
export function useDebouncedValue<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])

  return debounced
}
