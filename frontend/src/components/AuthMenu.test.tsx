import { focusManager } from '@tanstack/react-query'
import { act, cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect } from 'react'
import { useLocation } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../app/AppRoutes'
import { mockApi, type MockResponse } from '../test/mockApi'
import { renderWithProviders } from '../test/render'
import { AuthMenu } from './AuthMenu'

vi.mock('./MapView', () => ({ MapView: () => <div data-testid="map" /> }))

const ME = { id: 1, email: 'user@example.com', role: 'USER' }

/** 공통 레이아웃의 헤더만 */
const header = () => within(screen.getByRole('banner'))

describe('AuthMenu', () => {
  it('비로그인이면 지금 주소를 next에 실은 로그인 링크', async () => {
    mockApi({})
    renderWithProviders(<AuthMenu />, { route: '/complexes/11710-1?area=85' })

    expect(await screen.findByRole('link', { name: '로그인' })).toHaveAttribute(
      'href',
      '/login?next=%2Fcomplexes%2F11710-1%3Farea%3D85',
    )
  })

  it('확인 중에는 로그인 링크도 로그아웃 버튼도 없다', async () => {
    let respond: (value: { status: number }) => void = () => {}
    const server = mockApi({ 'GET /api/me': () => new Promise((resolve) => (respond = resolve)) })
    renderWithProviders(<AuthMenu />)

    await vi.waitFor(() => expect(server.to('/api/me')).toHaveLength(1))
    expect(screen.queryByRole('link', { name: '로그인' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()

    respond({ status: 401 })
    expect(await screen.findByRole('link', { name: '로그인' })).toBeInTheDocument()
  })

  it('확인에 실패하면(500) 오류 화면 없이 빈 자리만 둔다', async () => {
    const server = mockApi({ 'GET /api/me': { status: 500, body: { status: 500 } } })
    renderWithProviders(<AuthMenu />)

    // 재시도까지 끝나기를 기다린다
    await vi.waitFor(() => expect(server.to('/api/me')).toHaveLength(2))
    expect(screen.queryByRole('link', { name: '로그인' })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it.each(['/login', '/signup'])('%s 화면에서는 로그인 링크를 숨긴다', async (route) => {
    const server = mockApi({})
    renderWithProviders(<AuthMenu />, { route })

    await vi.waitFor(() => expect(server.to('/api/me')).toHaveLength(1))
    // 응답을 반영할 때까지 한 번 더 기다린 뒤 확인
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByRole('link', { name: '로그인' })).not.toBeInTheDocument()
  })

  it('로그인했으면 이메일과 로그아웃 버튼', async () => {
    mockApi({ 'GET /api/me': { body: ME } })
    renderWithProviders(<AuthMenu />)

    expect(await screen.findByText('user@example.com')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeEnabled()
    expect(screen.queryByRole('link', { name: '로그인' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '관심단지' })).toHaveAttribute('href', '/watchlist')
  })

  it('비로그인이면 관심단지 링크가 없다', async () => {
    mockApi({})
    renderWithProviders(<AuthMenu />)

    expect(await screen.findByRole('link', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '관심단지' })).not.toBeInTheDocument()
  })
})

describe('헤더에서 로그아웃', () => {
  it('첫 화면에서 로그아웃하면 그대로 첫 화면이고 헤더가 로그인 링크로 바뀐다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const server = mockApi({
      'GET /api/me': { body: ME },
      'POST /api/auth/logout': { status: 204 },
      '/api/regions': { body: [] },
    })
    renderWithProviders(<AppRoutes />, { route: '/' })

    await userEvent.click(await header().findByRole('button', { name: '로그아웃' }))

    expect(await header().findByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login?next=%2F')
    expect(header().queryByText('user@example.com')).not.toBeInTheDocument()
    expect(server.callsTo('POST', '/api/auth/logout')).toHaveLength(1)
    // 로그아웃 뒤 /api/me를 다시 묻지 않는다 (캐시에 null을 직접 넣음)
    expect(server.to('/api/me')).toHaveLength(1)
    expect(screen.getByTestId('location')).toHaveTextContent('/')
  })

  it('로그아웃 요청 중에는 버튼을 막고, 실패하면 이메일을 두고 실패를 알린다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    let respond: (value: { status: number; body: unknown }) => void = () => {}
    mockApi({
      'GET /api/me': { body: ME },
      'POST /api/auth/logout': () => new Promise((resolve) => (respond = resolve)),
      '/api/regions': { body: [] },
    })
    renderWithProviders(<AppRoutes />, { route: '/' })

    await userEvent.click(await header().findByRole('button', { name: '로그아웃' }))
    expect(await header().findByRole('button', { name: '로그아웃 중…' })).toBeDisabled()

    respond({ status: 500, body: { status: 500 } })

    expect(await header().findByRole('alert')).toHaveTextContent('로그아웃 실패')
    expect(header().getByText('user@example.com')).toBeInTheDocument()
    expect(header().getByRole('button', { name: '로그아웃' })).toBeEnabled()
  })

  /** 지나간 주소를 모두 적는다. 로그인 화면을 거쳤는지 확인용 */
  function LocationLog({ log }: { log: string[] }) {
    const location = useLocation()
    useEffect(() => {
      log.push(location.pathname + location.search)
    }, [location, log])
    return null
  }

  it('로그인이 필요한 화면에서 로그아웃하면 로그인 화면을 거치지 않고 첫 화면으로 간다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    mockApi({
      'GET /api/me': { body: ME },
      'GET /api/me/watchlist': { body: [] },
      'POST /api/auth/logout': { status: 204 },
      '/api/regions': { body: [] },
    })
    const log: string[] = []
    renderWithProviders(
      <>
        <AppRoutes />
        <LocationLog log={log} />
      </>,
      { route: '/watchlist' },
    )

    await screen.findByText('관심단지가 없어요')
    await userEvent.click(header().getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('heading', { name: '단지 검색' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
    expect(header().getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login?next=%2F')
    expect(log.some((path) => path.startsWith('/login'))).toBe(false)
  })

  it('공개 화면에서 로그아웃해도 첫 화면으로 간다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    mockApi({
      'GET /api/me': { body: ME },
      'POST /api/auth/logout': { status: 204 },
      '/api/complexes/11710-1': { status: 404, body: { status: 404 } },
      '/api/regions': { body: [] },
    })
    renderWithProviders(<AppRoutes />, { route: '/complexes/11710-1' })

    await userEvent.click(await header().findByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('heading', { name: '단지 검색' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
  })

  it('로그아웃이 실패하면 화면을 옮기지 않는다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    mockApi({
      'GET /api/me': { body: ME },
      'GET /api/me/watchlist': { body: [] },
      'POST /api/auth/logout': { status: 500, body: { status: 500 } },
    })
    renderWithProviders(<AppRoutes />, { route: '/watchlist' })

    await screen.findByText('관심단지가 없어요')
    await userEvent.click(header().getByRole('button', { name: '로그아웃' }))

    expect(await header().findByRole('alert')).toHaveTextContent('로그아웃 실패')
    expect(screen.getByTestId('location')).toHaveTextContent('/watchlist')
  })
})

describe('헤더 알림 링크와 안 읽은 수', () => {
  const COUNT = '/api/me/notifications/unread-count'

  function renderLoggedIn(count: MockResponse | (() => MockResponse)) {
    const server = mockApi({ 'GET /api/me': { body: ME }, [`GET ${COUNT}`]: count })
    renderWithProviders(<AuthMenu />)
    return server
  }

  it('로그인했으면 알림 링크, 비로그인이면 링크도 안 읽은 수 요청도 없다', async () => {
    renderLoggedIn({ body: { count: 0 } })
    expect(await screen.findByRole('link', { name: '알림' })).toHaveAttribute('href', '/notifications')
    cleanup()

    const server = mockApi({})
    renderWithProviders(<AuthMenu />)
    await screen.findByRole('link', { name: '로그인' })
    expect(screen.queryByRole('link', { name: /알림/ })).not.toBeInTheDocument()
    expect(server.to(COUNT)).toHaveLength(0)
  })

  it('안 읽은 알림이 있으면 배지와 개수를 담은 링크 이름', async () => {
    renderLoggedIn({ body: { count: 3 } })

    const link = await screen.findByRole('link', { name: '알림, 안 읽은 알림 3개' })
    expect(link).toHaveTextContent('알림3')
  })

  it('0개면 배지가 없다', async () => {
    const server = renderLoggedIn({ body: { count: 0 } })

    await vi.waitFor(() => expect(server.to(COUNT)).toHaveLength(1))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.getByRole('link', { name: '알림' })).toHaveTextContent(/^알림$/)
  })

  it('99개를 넘으면 99+ (링크 이름에는 실제 개수)', async () => {
    renderLoggedIn({ body: { count: 120 } })

    expect(await screen.findByRole('link', { name: '알림, 안 읽은 알림 120개' })).toHaveTextContent('알림99+')
  })

  it('안 읽은 수를 불러오지 못하면(500) 배지 없이 링크만', async () => {
    const server = renderLoggedIn({ status: 500, body: { status: 500 } })

    await vi.waitFor(() => expect(server.to(COUNT)).toHaveLength(2))
    expect(screen.getByRole('link', { name: '알림' })).toHaveTextContent(/^알림$/)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('60초마다 다시 묻는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      let count = 1
      const server = renderLoggedIn(() => ({ body: { count } }))
      await screen.findByRole('link', { name: '알림, 안 읽은 알림 1개' })
      const before = server.to(COUNT).length

      count = 2
      await vi.advanceTimersByTimeAsync(59_000)
      expect(server.to(COUNT)).toHaveLength(before)
      await vi.advanceTimersByTimeAsync(1_000)

      expect(await screen.findByRole('link', { name: '알림, 안 읽은 알림 2개' })).toBeInTheDocument()
      expect(server.to(COUNT)).toHaveLength(before + 1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('창으로 돌아오면 다시 묻는다 (staleTime 0이라 바로 전에 받았어도)', async () => {
    try {
      let count = 1
      const server = renderLoggedIn(() => ({ body: { count } }))
      await screen.findByRole('link', { name: '알림, 안 읽은 알림 1개' })
      const before = server.to(COUNT).length

      count = 0
      act(() => focusManager.setFocused(false))
      act(() => focusManager.setFocused(true))

      expect(await screen.findByRole('link', { name: '알림' })).toBeInTheDocument()
      expect(server.to(COUNT)).toHaveLength(before + 1)
    } finally {
      focusManager.setFocused(undefined)
    }
  })

  it('안 읽은 수가 401(다른 탭에서 로그아웃)이면 헤더가 로그인 링크로 바뀐다', async () => {
    let expired = false
    renderLoggedIn(() => (expired ? { status: 401, body: { status: 401 } } : { body: { count: 1 } }))
    await screen.findByRole('link', { name: '알림, 안 읽은 알림 1개' })

    expired = true
    act(() => focusManager.setFocused(false))
    act(() => focusManager.setFocused(true))

    try {
      expect(await screen.findByRole('link', { name: '로그인' })).toBeInTheDocument()
      expect(screen.queryByRole('link', { name: /알림/ })).not.toBeInTheDocument()
      expect(screen.queryByText('user@example.com')).not.toBeInTheDocument()
    } finally {
      focusManager.setFocused(undefined)
    }
  })
})
