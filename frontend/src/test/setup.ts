import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// jsdom은 scrollTo를 구현하지 않아 호출마다 경고를 찍는다 (ScrollToTop). 호출을 보려면 테스트에서 vi.stubGlobal로 바꾼다
window.scrollTo = () => {}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})
