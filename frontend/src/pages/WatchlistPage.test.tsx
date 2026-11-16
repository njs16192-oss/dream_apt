import { screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Schemas } from '../api/client'
import { AppRoutes } from '../app/AppRoutes'
import { mockApi } from '../test/mockApi'
import { renderWithProviders } from '../test/render'

vi.mock('../components/MapView', () => ({ MapView: () => <div data-testid="map" /> }))

const ME = { id: 1, email: 'user@example.com', role: 'USER' }
const WATCHLIST = '/api/me/watchlist'

function item(overrides: Partial<Schemas['WatchlistItem']> = {}): Schemas['WatchlistItem'] {
  return {
    aptSeq: '11710-8865',
    name: '헬리오시티',
    sggCd: '11710',
    sggName: '송파구',
    lastDealDate: '2026-10-12',
    addedAt: '2026-11-01T10:00:00Z',
    ...overrides,
  }
}

const header = () => within(screen.getByRole('banner'))
const main = () => within(screen.getByRole('main'))

describe('WatchlistPage', () => {
  it('비로그인이면 목록을 묻지 않고 로그인 화면으로 보낸다 (돌아올 곳은 next)', async () => {
    const server = mockApi({})
    renderWithProviders(<AppRoutes />, { route: '/watchlist' })

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Fwatchlist')
    expect(server.to(WATCHLIST)).toHaveLength(0)
  })

  it('로그인 확인 중에는 목록을 묻지 않는다', async () => {
    let respond: (value: { body: unknown }) => void = () => {}
    const server = mockApi({
      'GET /api/me': () => new Promise((resolve) => (respond = resolve)),
      [WATCHLIST]: { body: [] },
    })
    renderWithProviders(<AppRoutes />, { route: '/watchlist' })

    await vi.waitFor(() => expect(server.to('/api/me')).toHaveLength(1))
    expect(main().getByRole('status')).toBeInTheDocument()
    expect(server.to(WATCHLIST)).toHaveLength(0)

    respond({ body: ME })
    expect(await screen.findByText('관심단지가 없어요')).toBeInTheDocument()
    expect(server.to(WATCHLIST)).toHaveLength(1)
  })

  it('로그인 확인이 실패(500)하면 로그인 화면으로 보내지 않고 오류를 보여준다', async () => {
    mockApi({ 'GET /api/me': { status: 500, body: { status: 500 } } })
    renderWithProviders(<AppRoutes />, { route: '/watchlist' })

    expect(await screen.findByText('데이터를 불러오지 못했어요')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/watchlist')
  })

  it('받은 순서대로 단지 링크·구 이름·최근 거래일을 보여준다', async () => {
    mockApi({
      'GET /api/me': { body: ME },
      [WATCHLIST]: {
        body: [
          item(),
          item({ aptSeq: '11680-1', name: '은마', sggCd: '11680', sggName: '강남구', lastDealDate: '2026-09-03' }),
        ],
      },
    })
    renderWithProviders(<AppRoutes />, { route: '/watchlist' })

    const rows = await screen.findAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByRole('link', { name: '헬리오시티' })).toHaveAttribute('href', '/complexes/11710-8865')
    expect(rows[0]).toHaveTextContent('송파구')
    expect(rows[0]).toHaveTextContent('최근 거래 2026.10.12')
    expect(within(rows[1]).getByRole('link', { name: '은마' })).toHaveAttribute('href', '/complexes/11680-1')
    expect(rows[1]).toHaveTextContent('강남구')
    expect(main().getByText('2 / 50')).toBeInTheDocument()
    expect(document.title).toBe('관심단지 · Dream APT')
  })

  it('최근 일반 거래일이 없으면(거래 없음·일괄 거래만) "2020년 이후 일반 거래 없음"', async () => {
    mockApi({ 'GET /api/me': { body: ME }, [WATCHLIST]: { body: [item({ lastDealDate: null })] } })
    renderWithProviders(<AppRoutes />, { route: '/watchlist' })

    expect(await screen.findByText('2020년 이후 일반 거래 없음')).toBeInTheDocument()
    expect(screen.queryByText(/최근 거래/)).not.toBeInTheDocument()
  })

  it('비었으면 추가하는 방법과 검색 링크', async () => {
    mockApi({ 'GET /api/me': { body: ME }, [WATCHLIST]: { body: [] } })
    renderWithProviders(<AppRoutes />, { route: '/watchlist' })

    expect(await screen.findByText('관심단지가 없어요')).toBeInTheDocument()
    expect(main().getByText(/☆ 버튼을 눌러 추가하세요/)).toBeInTheDocument()
    expect(main().getByRole('link', { name: '단지 검색으로 가기' })).toHaveAttribute('href', '/')
    expect(main().getByText('0 / 50')).toBeInTheDocument()
  })

  it('목록이 500이면 다시 시도할 수 있다', async () => {
    let fail = true
    mockApi({
      'GET /api/me': { body: ME },
      [WATCHLIST]: () => (fail ? { status: 500, body: { status: 500 } } : { body: [item()] }),
    })
    renderWithProviders(<AppRoutes />, { route: '/watchlist' })

    const retry = await main().findByRole('button', { name: '다시 시도' })
    fail = false
    retry.click()

    expect(await main().findByRole('link', { name: '헬리오시티' })).toBeInTheDocument()
  })

  it('목록이 401(세션 만료)이면 헤더가 로그아웃 상태가 되고 로그인 화면으로 간다', async () => {
    mockApi({
      'GET /api/me': { body: ME },
      [WATCHLIST]: { status: 401, body: { status: 401, detail: '로그인이 필요합니다' } },
    })
    renderWithProviders(<AppRoutes />, { route: '/watchlist' })

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Fwatchlist')
    expect(header().queryByText('user@example.com')).not.toBeInTheDocument()
    expect(header().queryByRole('link', { name: '관심단지' })).not.toBeInTheDocument()
  })
})
