import { render } from '@testing-library/react'
import { StrictMode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Schemas } from '../api/client'
import { MapView } from './MapView'

// 실제 Leaflet으로 그린다. jsdom이라 크기는 0이지만 지도 생성·레이어 갱신·정리 과정에서 오류가 없는지 본다.
// 화면 모양·클릭 동작과 "바꾸자마자 다른 화면으로 이동"은 브라우저에서 확인한다: jsdom의 requestAnimationFrame과
// 가짜 canvas에서는 갱신 직후 정리하면 남은 다시 그리기가 'clearRect' of undefined를 내지만, 실제 브라우저(Edge)에서는
// 같은 순서를 20번 반복해도 오류가 없었다 (줌 애니메이션 문제는 zoomAnimation: false로 고침)
const nextFrames = () => new Promise((resolve) => setTimeout(resolve, 100))
const item = (aptSeq: string): Schemas['NearbyComplex'] => ({
  aptSeq,
  name: '헬리오시티',
  sggName: '송파구',
  umdName: '가락동',
  buildYear: 2018,
  lat: 37.499077,
  lng: 127.112296,
  distanceM: 0,
  recentTradeCount: 1,
})

const props = { onCenterChange: vi.fn(), onSelect: vi.fn() }

describe('MapView', () => {
  beforeEach(() => {
    // jsdom에는 canvas 구현이 없다(getContext가 null). preferCanvas 렌더러가 부르는 2D 메서드를 아무것도 안 하는 것으로 둔다
    const noop2d = new Proxy({}, { get: (_target, key) => (key === 'canvas' ? undefined : () => {}) })
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(noop2d as CanvasRenderingContext2D)
  })

  it('처음 그릴 때(지도에 보기 영역이 없을 때)도 반경에 맞춰 그리고, 바꾸고, 정리할 수 있다', async () => {
    // StrictMode: effect가 두 번 돌아도 같은 컨테이너에 지도를 두 번 만들지 않는다 (cleanup의 remove)
    const { container, rerender, unmount } = render(
      <StrictMode>
        <MapView center={{ lat: 37.5, lng: 127.1 }} radius={1000} items={[item('1')]} {...props} />
      </StrictMode>,
    )
    expect(container.querySelector('.leaflet-container')).not.toBeNull()

    rerender(
      <StrictMode>
        <MapView center={{ lat: 37.51, lng: 127.0 }} radius={3000} items={[item('1'), item('2')]} {...props} />
      </StrictMode>,
    )
    // 갱신으로 예약된 다시 그리기(requestAnimationFrame)가 끝난 뒤 정리한다 (위 주석)
    await nextFrames()
    unmount()
    expect(container.querySelector('.leaflet-container')).toBeNull()
    // 정리 뒤에도 남은 예약이 없어야 한다. 남아 있으면 테스트가 끝난 뒤 처리되지 않은 오류로 실패한다
    await nextFrames()
  })
})
