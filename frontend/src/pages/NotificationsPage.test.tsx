import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Schemas } from '../api/client'
import { AppRoutes } from '../app/AppRoutes'
import { mockApi, type MockResponse } from '../test/mockApi'
import { renderWithProviders } from '../test/render'

vi.mock('../components/MapView', () => ({ MapView: () => <div data-testid="map" /> }))

const ME = { id: 1, email: 'user@example.com', role: 'USER' }
const LIST = '/api/me/notifications'
const COUNT = '/api/me/notifications/unread-count'
const READ_ALL = '/api/me/notifications/read-all'
const readPath = (id: number) => `/api/me/notifications/${id}/read`

function notification(overrides: Partial<Schemas['NotificationItem']> = {}): Schemas['NotificationItem'] {
  return {
    id: 1,
    aptSeq: '11710-8865',
    complexName: '헬리오시티',
    areaGroup: 85,
    exclusiveArea: 84.99,
    floor: 12,
    dealDate: '2026-10-12',
    dealAmount: 245000,
    previousMaxAmount: 238000,
    createdAt: '2026-11-01T06:00:00Z',
    read: false,
    ...overrides,
  }
}

type Handler = () => MockResponse | Promise<MockResponse>

/**
 * 로그인한 회원 + 서버의 알림 상태를 흉내 낸다. 읽음 처리가 성공하면 다음 목록·안 읽은 수 요청에 반영된다.
 * read·readAll을 주면 그 응답을 쓰고 상태는 바꾸지 않는다 (실패 상황).
 */
function setupServer(
  initial: Schemas['NotificationItem'][],
  { read, readAll, extra = {} }: { read?: Handler; readAll?: Handler; extra?: Record<string, MockResponse> } = {},
) {
  document.cookie = 'XSRF-TOKEN=t; path=/'
  let items = initial.map((item) => ({ ...item }))
  const routes: Record<string, MockResponse | ((url: URL) => MockResponse | Promise<MockResponse>)> = {
    'GET /api/me': { body: ME },
    [`GET ${LIST}`]: () => ({
      body: { content: items, page: 0, size: 20, totalElements: items.length, totalPages: items.length > 0 ? 1 : 0 },
    }),
    [`GET ${COUNT}`]: () => ({ body: { count: items.filter((item) => !item.read).length } }),
    [`PUT ${READ_ALL}`]:
      readAll ??
      (() => {
        items = items.map((item) => ({ ...item, read: true }))
        return { status: 204 }
      }),
    '/api/complexes/11710-8865': { status: 404, body: { status: 404 } },
    ...extra,
  }
  for (const item of initial) {
    routes[`PUT ${readPath(item.id)}`] =
      read ??
      (() => {
        items = items.map((entry) => (entry.id === item.id ? { ...entry, read: true } : entry))
        return { status: 204 }
      })
  }
  return mockApi(routes)
}

const main = () => within(screen.getByRole('main'))
const readAllButton = () => main().getByRole('button', { name: '모두 읽음' })

describe('NotificationsPage', () => {
  it('비로그인이면 알림을 묻지 않고 로그인 화면으로 보낸다', async () => {
    const server = mockApi({})
    renderWithProviders(<AppRoutes />, { route: '/notifications' })

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Fnotifications')
    expect(server.to(LIST)).toHaveLength(0)
    expect(server.to(COUNT)).toHaveLength(0)
  })

  it('알림마다 단지 링크·최고가 배지·금액·이전 최고·면적·층·계약일을 보여준다', async () => {
    setupServer([notification()])
    renderWithProviders(<AppRoutes />, { route: '/notifications' })

    const [row] = await main().findAllByRole('listitem')
    expect(within(row).getByRole('link', { name: '헬리오시티' })).toHaveAttribute('href', '/complexes/11710-8865?area=85')
    expect(row).toHaveTextContent('2020년 이후 최고가')
    expect(row).toHaveTextContent('24억 5,000만')
    expect(row).toHaveTextContent('이전 최고 23억 8,000만')
    expect(row).toHaveTextContent('전용 84.99㎡ (25.7평) · 12층 · 계약 2026.10.12')
    expect(await main().findByText('안 읽은 알림 1개')).toBeInTheDocument()
    expect(document.title).toBe('알림 · Dream APT')
  })

  it('안 읽은 알림에만 "안 읽음" 표시와 읽음 버튼이 있다', async () => {
    setupServer([notification({ id: 1, complexName: '헬리오시티' }), notification({ id: 2, complexName: '은마', read: true })])
    renderWithProviders(<AppRoutes />, { route: '/notifications' })

    const [unread, read] = await main().findAllByRole('listitem')
    expect(within(unread).getByText('안 읽음')).toBeInTheDocument()
    expect(within(unread).getByRole('button', { name: '헬리오시티 알림 읽음' })).toBeEnabled()
    expect(within(read).queryByText('안 읽음')).not.toBeInTheDocument()
    expect(within(read).queryByRole('button')).not.toBeInTheDocument()
  })

  it('읽음을 누르면 CSRF 헤더를 붙여 PUT을 보내고, 목록과 안 읽은 수를 다시 받는다', async () => {
    const server = setupServer([notification({ id: 7 })])
    renderWithProviders(<AppRoutes />, { route: '/notifications' })
    await main().findByText('안 읽은 알림 1개')

    await userEvent.click(main().getByRole('button', { name: '헬리오시티 알림 읽음' }))

    expect(await main().findByText('안 읽은 알림 0개')).toBeInTheDocument()
    await vi.waitFor(() => expect(main().queryByRole('button', { name: '헬리오시티 알림 읽음' })).not.toBeInTheDocument())
    const puts = server.callsTo('PUT', readPath(7))
    expect(puts).toHaveLength(1)
    expect(puts[0].headers.get('X-XSRF-TOKEN')).toBe('t')
    expect(server.callsTo('GET', LIST)).toHaveLength(2)
    expect(server.callsTo('GET', COUNT)).toHaveLength(2)
    expect(main().queryByText('안 읽음')).not.toBeInTheDocument()
  })

  it('안 읽은 알림의 단지 링크를 누르면 읽음 처리하고 단지로 간다', async () => {
    const server = setupServer([notification({ id: 7 })])
    renderWithProviders(<AppRoutes />, { route: '/notifications' })

    await userEvent.click(await main().findByRole('link', { name: '헬리오시티' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/complexes/11710-8865?area=85')
    await vi.waitFor(() => expect(server.callsTo('PUT', readPath(7))).toHaveLength(1))
  })

  it('이미 읽은 알림의 단지 링크는 읽음 요청을 보내지 않는다', async () => {
    const server = setupServer([notification({ id: 7, read: true })])
    renderWithProviders(<AppRoutes />, { route: '/notifications' })

    await userEvent.click(await main().findByRole('link', { name: '헬리오시티' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/complexes/11710-8865?area=85')
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(server.callsTo('PUT', readPath(7))).toHaveLength(0)
  })

  it('모두 읽음을 누르면 PUT read-all을 보내고 두 가지를 다시 받는다. 다 읽으면 버튼이 비활성', async () => {
    const server = setupServer([notification({ id: 1 }), notification({ id: 2, complexName: '은마' })])
    renderWithProviders(<AppRoutes />, { route: '/notifications' })
    await main().findByText('안 읽은 알림 2개')

    await userEvent.click(readAllButton())

    expect(await main().findByText('안 읽은 알림 0개')).toBeInTheDocument()
    expect(server.callsTo('PUT', READ_ALL)).toHaveLength(1)
    expect(server.callsTo('PUT', READ_ALL)[0].headers.get('X-XSRF-TOKEN')).toBe('t')
    expect(server.callsTo('GET', LIST)).toHaveLength(2)
    expect(readAllButton()).toBeDisabled()
    expect(main().queryByText('안 읽음')).not.toBeInTheDocument()
  })

  it('안 읽은 알림이 없거나 수를 모르면 모두 읽음을 누를 수 없다', async () => {
    setupServer([notification({ read: true })])
    const first = renderWithProviders(<AppRoutes />, { route: '/notifications' })
    await main().findByText('안 읽은 알림 0개')
    expect(readAllButton()).toBeDisabled()
    first.unmount()

    setupServer([notification()], { extra: { [`GET ${COUNT}`]: { status: 500, body: { status: 500 } } } })
    renderWithProviders(<AppRoutes />, { route: '/notifications' })
    await main().findByRole('link', { name: '헬리오시티' })
    expect(readAllButton()).toBeDisabled()
    expect(main().queryByText(/안 읽은 알림 \d+개/)).not.toBeInTheDocument()
  })

  it('?page=2면 API에 page=1, size=20으로 요청하고 페이지 이동을 둔다', async () => {
    const server = mockApi({
      'GET /api/me': { body: ME },
      [`GET ${COUNT}`]: { body: { count: 0 } },
      [`GET ${LIST}`]: {
        body: { content: [notification({ read: true })], page: 1, size: 20, totalElements: 41, totalPages: 3 },
      },
    })
    renderWithProviders(<AppRoutes />, { route: '/notifications?page=2' })

    const nav = within(await main().findByRole('navigation', { name: '페이지' }))
    expect(nav.getByText('2 / 3')).toBeInTheDocument()
    expect(nav.getByRole('link', { name: '이전' })).toHaveAttribute('href', '/notifications')
    expect(nav.getByRole('link', { name: '다음' })).toHaveAttribute('href', '/notifications?page=3')
    const [request] = server.to(LIST)
    expect(request.searchParams.get('page')).toBe('1')
    expect(request.searchParams.get('size')).toBe('20')
  })

  it('알림이 없으면 안내와 관심단지 링크', async () => {
    setupServer([])
    renderWithProviders(<AppRoutes />, { route: '/notifications' })

    expect(await main().findByText('아직 알림이 없어요')).toBeInTheDocument()
    expect(main().getByText(/2020년 이후 최고가 거래가 나오면 알려 드려요/)).toBeInTheDocument()
    expect(main().getByRole('link', { name: '관심단지 보기' })).toHaveAttribute('href', '/watchlist')
  })

  it('범위 밖 페이지면 안내와 첫 페이지 링크', async () => {
    mockApi({
      'GET /api/me': { body: ME },
      [`GET ${COUNT}`]: { body: { count: 0 } },
      [`GET ${LIST}`]: { body: { content: [], page: 9, size: 20, totalElements: 3, totalPages: 1 } },
    })
    renderWithProviders(<AppRoutes />, { route: '/notifications?page=10' })

    expect(await main().findByText('이 페이지에 알림이 없어요')).toBeInTheDocument()
    expect(main().getByRole('link', { name: '첫 페이지로' })).toHaveAttribute('href', '/notifications')
  })

  it('목록이 500이면 다시 시도할 수 있다', async () => {
    let fail = true
    mockApi({
      'GET /api/me': { body: ME },
      [`GET ${COUNT}`]: { body: { count: 1 } },
      [`GET ${LIST}`]: () =>
        fail
          ? { status: 500, body: { status: 500 } }
          : { body: { content: [notification()], page: 0, size: 20, totalElements: 1, totalPages: 1 } },
    })
    renderWithProviders(<AppRoutes />, { route: '/notifications' })

    const retry = await main().findByRole('button', { name: '다시 시도' })
    fail = false
    await userEvent.click(retry)

    expect(await main().findByRole('link', { name: '헬리오시티' })).toBeInTheDocument()
  })

  it('읽음 처리가 실패하면 그 알림에 안내를 보여주고, 목록은 다시 받지 않는다', async () => {
    const server = setupServer([notification({ id: 7 })], { read: () => ({ status: 500, body: { status: 500 } }) })
    renderWithProviders(<AppRoutes />, { route: '/notifications' })
    await main().findByText('안 읽은 알림 1개')

    await userEvent.click(main().getByRole('button', { name: '헬리오시티 알림 읽음' }))

    const [row] = main().getAllByRole('listitem')
    expect(await within(row).findByRole('alert')).toHaveTextContent('읽음 처리하지 못했어요')
    expect(within(row).getByRole('button', { name: '헬리오시티 알림 읽음' })).toBeEnabled()
    expect(server.callsTo('GET', LIST)).toHaveLength(1)
    expect(server.callsTo('GET', COUNT)).toHaveLength(1)
  })

  it('모두 읽음이 실패하면 안내하고 다시 누를 수 있다', async () => {
    setupServer([notification()], { readAll: () => ({ status: 500, body: { status: 500 } }) })
    renderWithProviders(<AppRoutes />, { route: '/notifications' })
    await main().findByText('안 읽은 알림 1개')

    await userEvent.click(readAllButton())

    expect(await main().findByRole('alert')).toHaveTextContent('모두 읽음 처리하지 못했어요')
    expect(readAllButton()).toBeEnabled()
    expect(main().getByText('안 읽음')).toBeInTheDocument()
  })

  it('처리 중에는 읽음 버튼을 누를 수 없어서 요청이 한 번이다', async () => {
    let respond: (value: MockResponse) => void = () => {}
    const server = setupServer([notification({ id: 7 })], { read: () => new Promise((resolve) => (respond = resolve)) })
    renderWithProviders(<AppRoutes />, { route: '/notifications' })
    const button = await main().findByRole('button', { name: '헬리오시티 알림 읽음' })

    await userEvent.click(button)
    await vi.waitFor(() => expect(button).toBeDisabled())
    await userEvent.click(button)

    expect(server.callsTo('PUT', readPath(7))).toHaveLength(1)
    respond({ status: 204 })
  })

  it('읽음 처리가 401(세션 만료)이면 로그인 화면으로 간다', async () => {
    setupServer([notification({ id: 7 })], { read: () => ({ status: 401, body: { status: 401 } }) })
    renderWithProviders(<AppRoutes />, { route: '/notifications' })

    await userEvent.click(await main().findByRole('button', { name: '헬리오시티 알림 읽음' }))

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Fnotifications')
  })

  it('모두 읽음이 401(세션 만료)이어도 로그인 화면으로 간다', async () => {
    setupServer([notification()], { readAll: () => ({ status: 401, body: { status: 401 } }) })
    renderWithProviders(<AppRoutes />, { route: '/notifications' })
    await main().findByText('안 읽은 알림 1개')

    await userEvent.click(readAllButton())

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Fnotifications')
  })
})
