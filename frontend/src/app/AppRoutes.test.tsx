import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { mockApi } from '../test/mockApi'
import { renderWithProviders } from '../test/render'
import { AppRoutes } from './AppRoutes'

// jsdom에서는 지도를 그리지 않는다
vi.mock('../components/MapView', () => ({ MapView: () => <div data-testid="map" /> }))

describe('AppRoutes', () => {
  it('/ 는 검색 페이지를 공통 레이아웃 안에 보여준다', () => {
    renderWithProviders(<AppRoutes />, { route: '/' })

    expect(screen.getByRole('heading', { name: '단지 검색' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Dream APT' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: '지도' })).toHaveAttribute('href', '/map')
    expect(screen.getByText(/출처: 국토교통부/)).toBeInTheDocument()
  })

  it('지도 화면은 따로 불러온 뒤 반경 검색을 요청한다', async () => {
    const server = mockApi({
      '/api/complexes/nearby': { body: { lat: 37.5665, lng: 126.978, radius: 1000, totalCount: 0, items: [] } },
    })
    renderWithProviders(<AppRoutes />, { route: '/map' })

    expect(await screen.findByRole('heading', { name: '주변 단지' })).toBeInTheDocument()
    expect(await screen.findByText('반경 안에 단지가 없어요')).toBeInTheDocument()
    expect(server.to('/api/complexes/nearby')).toHaveLength(1)
  })

  it('단지 상세는 경로의 aptSeq로 단지를 불러온다', async () => {
    const server = mockApi({ '/api/complexes/11710-1234': { status: 404, body: { status: 404 } } })
    renderWithProviders(<AppRoutes />, { route: '/complexes/11710-1234?area=85' })

    expect(await screen.findByText('단지를 찾을 수 없어요')).toBeInTheDocument()
    expect(server.to('/api/complexes/11710-1234')).toHaveLength(1)
  })

  it('구 거래량은 경로의 sggCd로 거래량을 불러온다', async () => {
    const server = mockApi({ '/api/regions/11710/monthly': { status: 404, body: { status: 404 } } })
    renderWithProviders(<AppRoutes />, { route: '/regions/11710' })

    expect(await screen.findByText('구를 찾을 수 없어요')).toBeInTheDocument()
    expect(server.to('/api/regions/11710/monthly')).toHaveLength(1)
  })

  it('없는 경로는 404 안내를 보여준다', () => {
    renderWithProviders(<AppRoutes />, { route: '/no/such/page' })

    expect(screen.getByText('페이지를 찾을 수 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '단지 검색으로 가기' })).toHaveAttribute('href', '/')
  })
})
