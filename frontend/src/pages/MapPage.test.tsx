import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { Schemas } from '../api/client'
import { mockApi, type MockResponse } from '../test/mockApi'
import { renderWithProviders } from '../test/render'
import { MapPage } from './MapPage'

// jsdom에서는 지도를 그리지 않는다. 받은 props를 기록하고 지도 클릭·마커 선택은 콜백을 직접 불러 흉내 낸다
const map = vi.hoisted(() => ({ props: null as null | Record<string, unknown> }))
vi.mock('../components/MapView', () => ({
  MapView: (props: Record<string, unknown>) => {
    map.props = props
    return <div data-testid="map" />
  },
}))
const mapProps = () =>
  map.props as {
    center: { lat: number; lng: number }
    radius: number
    items: Schemas['NearbyComplex'][]
    onCenterChange: (lat: number, lng: number) => void
    onSelect: (aptSeq: string) => void
  }

const NEARBY = '/api/complexes/nearby'
const HELIO = '/map?lat=37.499077&lng=127.112296&r=1000'

const item = (aptSeq: string, name: string, distanceM: number): Schemas['NearbyComplex'] => ({
  aptSeq,
  name,
  sggName: '송파구',
  umdName: '가락동',
  buildYear: 2018,
  lat: 37.5,
  lng: 127.1,
  distanceM,
  recentTradeCount: 152,
})

function nearby(items: Schemas['NearbyComplex'][], totalCount = items.length, radius = 1000): MockResponse {
  return { body: { lat: 37.499077, lng: 127.112296, radius, totalCount, items } }
}

function renderPage(entries = ['/start', HELIO]) {
  return renderWithProviders(
    <Routes>
      <Route path="/map" element={<MapPage />} />
      <Route path="/complexes/:aptSeq" element={<p>단지 상세</p>} />
      <Route path="/start" element={<p>시작</p>} />
    </Routes>,
    { entries },
  )
}

const location = () => screen.getByTestId('location').textContent
const radiusButton = (name: string) => within(screen.getByRole('group', { name: '반경' })).getByRole('button', { name })

describe('MapPage', () => {
  it('주소가 없으면 기본 중심(서울시청)·1km로 replace하고 그 값으로 한 번만 요청한다', async () => {
    const server = mockApi({ [NEARBY]: nearby([]) })
    renderPage(['/start', '/map'])

    expect(await screen.findByText('반경 안에 단지가 없어요')).toBeInTheDocument()
    expect(location()).toBe('/map?lat=37.5665&lng=126.978&r=1000')
    expect(server.to(NEARBY)).toHaveLength(1)
    expect(Object.fromEntries(server.to(NEARBY)[0].searchParams)).toEqual({
      lat: '37.5665',
      lng: '126.978',
      radius: '1000',
      limit: '300',
    })
    // 보정은 replace: 뒤로 가면 보정 전 주소(/map)가 아니라 그 전 화면으로 간다
    await userEvent.click(screen.getByTestId('history-back'))
    expect(location()).toBe('/start')
  })

  it('가까운 순 목록을 거리·위치·최근 거래 수와 함께 보여주고 단지 상세로 링크한다', async () => {
    mockApi({ [NEARBY]: nearby([item('11710-8865', '헬리오시티', 0), item('11710-37', '가락삼익', 1250)]) })
    renderPage()

    const list = await screen.findByRole('region', { name: '주변 단지 목록' })
    expect(within(list).getByText('반경 1km 안 2개')).toBeInTheDocument()
    const links = within(list).getAllByRole('link')
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/complexes/11710-8865', '/complexes/11710-37'])
    expect(links[1]).toHaveTextContent('가락삼익')
    expect(links[1]).toHaveTextContent('1.3km · 송파구 가락동 · 2018년 준공')
    expect(links[1]).toHaveTextContent('152건')
    expect(document.title).toBe('주변 단지 · Dream APT')
  })

  it('반경 안 단지가 limit보다 많으면 잘렸다고 알린다', async () => {
    mockApi({ [NEARBY]: nearby([item('11500-102', '단지', 0), item('11500-1', '단지2', 10)], 974, 3000) })
    renderPage(['/map?lat=37.534414&lng=126.858147&r=3000'])

    expect(await screen.findByText('반경 3km 안 974개 중 가까운 2개만 보여요 (최대 300개)')).toBeInTheDocument()
  })

  it('지도에 중심·반경·단지를 넘긴다', async () => {
    mockApi({ [NEARBY]: nearby([item('11710-8865', '헬리오시티', 0)]) })
    renderPage()

    await screen.findByRole('region', { name: '주변 단지 목록' })
    expect(mapProps().center).toMatchObject({ lat: 37.499077, lng: 127.112296 })
    expect(mapProps().radius).toBe(1000)
    expect(mapProps().items.map((i) => i.aptSeq)).toEqual(['11710-8865'])
  })

  it('반경을 바꾸면 URL을 replace하고 그 반경으로 다시 요청한다', async () => {
    const server = mockApi({ [NEARBY]: (url) => nearby([], 0, Number(url.searchParams.get('radius'))) })
    renderPage()
    await screen.findByText('반경 안에 단지가 없어요')
    expect(radiusButton('1km')).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(radiusButton('2km'))

    expect(location()).toBe('/map?lat=37.499077&lng=127.112296&r=2000')
    expect(radiusButton('2km')).toHaveAttribute('aria-pressed', 'true')
    await vi.waitFor(() => expect(server.to(NEARBY)).toHaveLength(2))
    expect(server.to(NEARBY)[1].searchParams.get('radius')).toBe('2000')
    await userEvent.click(screen.getByTestId('history-back'))
    expect(location()).toBe('/start')
  })

  it('지도를 누르면 그 위치를 중심으로 URL을 replace하고 다시 요청한다', async () => {
    const server = mockApi({ [NEARBY]: nearby([]) })
    renderPage()
    await screen.findByText('반경 안에 단지가 없어요')

    act(() => mapProps().onCenterChange(37.51234567, 127.0012344))

    expect(location()).toBe('/map?lat=37.512346&lng=127.001234&r=1000')
    await vi.waitFor(() => expect(server.to(NEARBY)).toHaveLength(2))
    expect(server.to(NEARBY)[1].searchParams.get('lat')).toBe('37.512346')
    await userEvent.click(screen.getByTestId('history-back'))
    expect(location()).toBe('/start')
  })

  it('마커를 고르면 라우터로 단지 상세에 간다 (push)', async () => {
    mockApi({ [NEARBY]: nearby([item('11710-8865', '헬리오시티', 0)]) })
    renderPage()
    await screen.findByRole('region', { name: '주변 단지 목록' })

    act(() => mapProps().onSelect('11710-8865'))

    expect(await screen.findByText('단지 상세')).toBeInTheDocument()
    expect(location()).toBe('/complexes/11710-8865')
    await userEvent.click(screen.getByTestId('history-back'))
    expect(location()).toBe(HELIO)
  })

  it('중심을 바꾸는 동안 이전 목록을 흐리게 남긴다', async () => {
    let resolveSecond: (response: MockResponse) => void = () => {}
    let calls = 0
    mockApi({
      [NEARBY]: () => {
        calls += 1
        return calls === 1
          ? nearby([item('11710-8865', '헬리오시티', 0)])
          : new Promise<MockResponse>((resolve) => (resolveSecond = resolve))
      },
    })
    renderPage()
    await screen.findByRole('region', { name: '주변 단지 목록' })

    act(() => mapProps().onCenterChange(37.51, 127.0))

    await vi.waitFor(() => expect(calls).toBe(2))
    const list = screen.getByRole('region', { name: '주변 단지 목록' })
    expect(within(list).getByText('헬리오시티')).toBeInTheDocument()
    expect(list).toHaveClass('opacity-50')
    act(() => resolveSecond(nearby([])))
    expect(await screen.findByText('반경 안에 단지가 없어요')).toBeInTheDocument()
  })

  it('서버 오류면 다시 시도할 수 있고, 4xx면 버튼 없이 안내한다', async () => {
    mockApi({ [NEARBY]: { status: 500, body: { status: 500 } } })
    const { unmount } = renderPage()
    expect(await screen.findByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    unmount()

    mockApi({ [NEARBY]: { status: 400, body: { status: 400 } } })
    renderPage()
    expect(await screen.findByText('요청을 처리할 수 없어요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })
})
