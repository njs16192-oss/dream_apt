import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// jsdom은 scrollTo를 구현하지 않아 호출마다 경고를 찍는다 (ScrollToTop). 호출을 보려면 테스트에서 vi.stubGlobal로 바꾼다
window.scrollTo = () => {}

/** 테스트 사이에 쿠키(XSRF-TOKEN 등)가 남지 않게 모두 지운다 */
function clearCookies() {
  for (const pair of document.cookie.split(';')) {
    const name = pair.split('=')[0].trim()
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
  }
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  clearCookies()
})
