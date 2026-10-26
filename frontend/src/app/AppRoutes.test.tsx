import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { mockApi } from '../test/mockApi'
import { renderWithProviders } from '../test/render'
import { AppRoutes } from './AppRoutes'

describe('AppRoutes', () => {
  it('/ 는 검색 페이지를 공통 레이아웃 안에 보여준다', () => {
    renderWithProviders(<AppRoutes />, { route: '/' })

    expect(screen.getByRole('heading', { name: '단지 검색' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Dream APT' })).toHaveAttribute('href', '/')
    expect(screen.getByText(/출처: 국토교통부/)).toBeInTheDocument()
  })

  it('단지 상세는 경로의 aptSeq로 단지를 불러온다', async () => {
    const server = mockApi({ '/api/complexes/11710-1234': { status: 404, body: { status: 404 } } })
    renderWithProviders(<AppRoutes />, { route: '/complexes/11710-1234?area=85' })

    expect(await screen.findByText('단지를 찾을 수 없어요')).toBeInTheDocument()
    expect(server.to('/api/complexes/11710-1234')).toHaveLength(1)
  })

  it('구 거래량 경로', () => {
    renderWithProviders(<AppRoutes />, { route: '/regions/11710' })
    expect(screen.getByRole('heading', { name: '구 거래량 11710' })).toBeInTheDocument()
  })

  it('없는 경로는 404 안내를 보여준다', () => {
    renderWithProviders(<AppRoutes />, { route: '/no/such/page' })

    expect(screen.getByText('페이지를 찾을 수 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '단지 검색으로 가기' })).toHaveAttribute('href', '/')
  })
})
