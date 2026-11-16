import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Schemas } from '../api/client'
import { AppRoutes } from '../app/AppRoutes'
import { mockApi, type MockResponse } from '../test/mockApi'
import { renderWithProviders } from '../test/render'
import { WatchButton } from './WatchButton'

const APT_SEQ = '11710-8865'
const ROUTE = `/complexes/${APT_SEQ}?area=85`
const ME = { id: 1, email: 'user@example.com', role: 'USER' }
const WATCHLIST = '/api/me/watchlist'

function item(aptSeq: string): Schemas['WatchlistItem'] {
  return { aptSeq, name: '단지', sggCd: '11710', sggName: '송파구', lastDealDate: null, addedAt: '2026-11-01T10:00:00Z' }
}

const button = () => screen.getByRole('button', { name: '관심단지' })

describe('WatchButton 표시', () => {
  it('비로그인이면 이 단지로 돌아오는 로그인 링크이고, 관심단지 목록을 묻지 않는다', async () => {
    const server = mockApi({})
    renderWithProviders(<WatchButton aptSeq={APT_SEQ} />, { route: ROUTE })

    expect(await screen.findByRole('link', { name: '관심단지' })).toHaveAttribute(
      'href',
      '/login?next=%2Fcomplexes%2F11710-8865%3Farea%3D85',
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(server.to(WATCHLIST)).toHaveLength(0)
  })

  it('로그인 확인 중에는 링크도 버튼도 없다', async () => {
    const server = mockApi({ 'GET /api/me': () => new Promise(() => {}) })
    renderWithProviders(<WatchButton aptSeq={APT_SEQ} />, { route: ROUTE })

    await vi.waitFor(() => expect(server.to('/api/me')).toHaveLength(1))
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('내 목록에 있으면 눌린 상태(★)', async () => {
    mockApi({ 'GET /api/me': { body: ME }, [WATCHLIST]: { body: [item('11680-1'), item(APT_SEQ)] } })
    renderWithProviders(<WatchButton aptSeq={APT_SEQ} />, { route: ROUTE })

    await vi.waitFor(() => expect(button()).toBeEnabled())
    expect(button()).toHaveAttribute('aria-pressed', 'true')
    expect(button()).toHaveTextContent('★')
  })

  it('내 목록에 없으면 안 눌린 상태(☆)', async () => {
    mockApi({ 'GET /api/me': { body: ME }, [WATCHLIST]: { body: [item('11680-1')] } })
    renderWithProviders(<WatchButton aptSeq={APT_SEQ} />, { route: ROUTE })

    await vi.waitFor(() => expect(button()).toBeEnabled())
    expect(button()).toHaveAttribute('aria-pressed', 'false')
    expect(button()).toHaveTextContent('☆')
  })

  it('목록을 불러오는 동안에는 누를 수 없다', async () => {
    const server = mockApi({ 'GET /api/me': { body: ME }, [WATCHLIST]: () => new Promise(() => {}) })
    renderWithProviders(<WatchButton aptSeq={APT_SEQ} />, { route: ROUTE })

    await vi.waitFor(() => expect(server.to(WATCHLIST)).toHaveLength(1))
    expect(button()).toBeDisabled()
    expect(button()).toHaveAttribute('aria-pressed', 'false')
  })

  it('목록을 불러오지 못하면(500) 누를 수 없고 이유를 title로 알린다', async () => {
    const server = mockApi({ 'GET /api/me': { body: ME }, [WATCHLIST]: { status: 500, body: { status: 500 } } })
    renderWithProviders(<WatchButton aptSeq={APT_SEQ} />, { route: ROUTE })

    // 재시도까지 끝나기를 기다린다
    await vi.waitFor(() => expect(button()).toHaveAttribute('title', '관심단지 목록을 불러오지 못했어요'))
    expect(server.to(WATCHLIST)).toHaveLength(2)
    expect(button()).toBeDisabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('WatchButton 추가·해제', () => {
  const ITEM = `${WATCHLIST}/${APT_SEQ}`

  type Handler = () => MockResponse | Promise<MockResponse>

  /**
   * 로그인한 회원 + 서버의 관심단지 목록을 흉내 낸다. PUT·DELETE가 성공하면 목록이 바뀌어 다음 GET에 반영된다.
   * put·del을 주면 그 응답을 쓰고 목록은 바꾸지 않는다 (실패 상황).
   */
  function setupServer({ watched = false, put, del }: { watched?: boolean; put?: Handler; del?: Handler } = {}) {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    let list = watched ? [item(APT_SEQ)] : [item('11680-1')]
    return mockApi({
      'GET /api/me': { body: ME },
      [`GET ${WATCHLIST}`]: () => ({ body: list }),
      [`PUT ${ITEM}`]:
        put ??
        (() => {
          list = [{ ...item(APT_SEQ), name: '헬리오시티' }, ...list]
          return { status: 204 }
        }),
      [`DELETE ${ITEM}`]:
        del ??
        (() => {
          list = list.filter((entry) => entry.aptSeq !== APT_SEQ)
          return { status: 204 }
        }),
      [`/api/complexes/${APT_SEQ}`]: { body: complexDetail() },
      '/api/regions': { body: [] },
    })
  }

  /** 면적 그룹이 없는 단지: 상세 화면이 거래·가격을 요청하지 않는다 */
  function complexDetail(): Schemas['ComplexDetail'] {
    return {
      aptSeq: APT_SEQ,
      name: '헬리오시티',
      sggCd: '11710',
      sggName: '송파구',
      umdName: '가락동',
      buildYear: 2018,
      roadAddress: null,
      lat: null,
      lng: null,
      tradeCount: 0,
      bulkCount: 0,
      bulkRatio: 0,
      bulkDominant: false,
      areaGroups: [],
    }
  }

  async function renderReady() {
    renderWithProviders(<WatchButton aptSeq={APT_SEQ} />, { route: ROUTE })
    await vi.waitFor(() => expect(button()).toBeEnabled())
  }

  it('추가하면 CSRF 헤더를 붙여 PUT을 보내고, 목록을 다시 받아 눌린 상태가 된다', async () => {
    const server = setupServer()
    await renderReady()
    expect(button()).toHaveAttribute('aria-pressed', 'false')

    await userEvent.click(button())

    await vi.waitFor(() => expect(button()).toHaveAttribute('aria-pressed', 'true'))
    const puts = server.callsTo('PUT', ITEM)
    expect(puts).toHaveLength(1)
    expect(puts[0].headers.get('X-XSRF-TOKEN')).toBe('t')
    expect(server.callsTo('DELETE', ITEM)).toHaveLength(0)
    expect(server.callsTo('GET', WATCHLIST)).toHaveLength(2)
    expect(button()).toBeEnabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('추가한 뒤 관심단지 화면으로 가면 목록에 보인다', async () => {
    setupServer()
    renderWithProviders(<AppRoutes />, { route: ROUTE })
    const main = within(screen.getByRole('main'))
    const watch = () => main.getByRole('button', { name: '관심단지' })
    await vi.waitFor(() => expect(watch()).toBeEnabled())

    await userEvent.click(watch())
    await vi.waitFor(() => expect(watch()).toHaveAttribute('aria-pressed', 'true'))
    await userEvent.click(within(screen.getByRole('navigation', { name: '주요 메뉴' })).getByRole('link', { name: '관심단지' }))

    expect(await main.findByRole('link', { name: '헬리오시티' })).toHaveAttribute('href', `/complexes/${APT_SEQ}`)
    expect(main.getByText('2 / 50')).toBeInTheDocument()
  })

  it('눌린 상태에서 누르면 DELETE를 보내고 안 눌린 상태가 된다', async () => {
    const server = setupServer({ watched: true })
    await renderReady()
    expect(button()).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(button())

    await vi.waitFor(() => expect(button()).toHaveAttribute('aria-pressed', 'false'))
    const deletes = server.callsTo('DELETE', ITEM)
    expect(deletes).toHaveLength(1)
    expect(deletes[0].headers.get('X-XSRF-TOKEN')).toBe('t')
    expect(server.callsTo('PUT', ITEM)).toHaveLength(0)
  })

  it('409(50개 초과)면 서버 문구를 보여주고 상태는 그대로다', async () => {
    setupServer({
      put: () => ({ status: 409, body: { title: 'Conflict', status: 409, detail: '관심단지는 최대 50개까지입니다' } }),
    })
    await renderReady()

    await userEvent.click(button())

    expect(await screen.findByRole('alert')).toHaveTextContent('관심단지는 최대 50개까지입니다')
    expect(button()).toHaveAttribute('aria-pressed', 'false')
    expect(button()).toBeEnabled()
  })

  it('409인데 서버 문구가 없으면 기본 문구', async () => {
    setupServer({ put: () => ({ status: 409, body: { status: 409 } }) })
    await renderReady()

    await userEvent.click(button())

    expect(await screen.findByRole('alert')).toHaveTextContent('관심단지는 최대 50개까지예요')
  })

  it('500이면 다시 시도 안내, 다시 눌러 성공하면 문구가 사라진다', async () => {
    let fail = true
    let added = false
    document.cookie = 'XSRF-TOKEN=t; path=/'
    mockApi({
      'GET /api/me': { body: ME },
      [`GET ${WATCHLIST}`]: () => ({ body: added ? [item(APT_SEQ)] : [] }),
      [`PUT ${ITEM}`]: () => {
        if (fail) return { status: 500, body: { status: 500 } }
        added = true
        return { status: 204 }
      },
    })
    await renderReady()

    await userEvent.click(button())
    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요')
    expect(button()).toHaveAttribute('aria-pressed', 'false')

    fail = false
    await userEvent.click(button())

    await vi.waitFor(() => expect(button()).toHaveAttribute('aria-pressed', 'true'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('추가가 500으로 실패해도 목록을 다시 요청한다 (서버에 반영됐는지 모르므로 서버 상태로 맞춘다)', async () => {
    const server = setupServer({ put: () => ({ status: 500, body: { status: 500 } }) })
    await renderReady()
    expect(server.callsTo('GET', WATCHLIST)).toHaveLength(1)

    await userEvent.click(button())

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요')
    await vi.waitFor(() => expect(server.callsTo('GET', WATCHLIST)).toHaveLength(2))
  })

  it('해제가 실패하면 안내하고 눌린 상태 그대로다', async () => {
    setupServer({ watched: true, del: () => ({ status: 500, body: { status: 500 } }) })
    await renderReady()

    await userEvent.click(button())

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요')
    expect(button()).toHaveAttribute('aria-pressed', 'true')
  })

  it('PUT이 401(세션 만료)이면 헤더가 로그인 링크로 바뀌고 버튼도 로그인 링크가 된다', async () => {
    setupServer({ put: () => ({ status: 401, body: { status: 401, detail: '로그인이 필요합니다' } }) })
    renderWithProviders(<AppRoutes />, { route: ROUTE })
    const main = within(screen.getByRole('main'))
    await vi.waitFor(() => expect(main.getByRole('button', { name: '관심단지' })).toBeEnabled())

    await userEvent.click(main.getByRole('button', { name: '관심단지' }))

    const header = within(screen.getByRole('navigation', { name: '주요 메뉴' }))
    expect(await header.findByRole('link', { name: '로그인' })).toBeInTheDocument()
    expect(header.queryByText('user@example.com')).not.toBeInTheDocument()
    expect(main.getByRole('link', { name: '관심단지' })).toHaveAttribute(
      'href',
      `/login?next=${encodeURIComponent(ROUTE)}`,
    )
    expect(main.queryByRole('button', { name: '관심단지' })).not.toBeInTheDocument()
  })

  it('해제(DELETE)가 401이어도 로그아웃 상태가 된다', async () => {
    setupServer({ watched: true, del: () => ({ status: 401, body: { status: 401 } }) })
    await renderReady()

    await userEvent.click(button())

    expect(await screen.findByRole('link', { name: '관심단지' })).toHaveAttribute(
      'href',
      `/login?next=${encodeURIComponent(ROUTE)}`,
    )
  })

  it('추가한 뒤 목록을 다시 받을 때까지는 누를 수 없다 (옛 상태로 한 번 더 누르지 않게)', async () => {
    let added = false
    let releaseList: () => void = () => {}
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const server = mockApi({
      'GET /api/me': { body: ME },
      [`GET ${WATCHLIST}`]: () =>
        added
          ? new Promise<MockResponse>((resolve) => (releaseList = () => resolve({ body: [item(APT_SEQ)] })))
          : { body: [] },
      [`PUT ${ITEM}`]: () => {
        added = true
        return { status: 204 }
      },
    })
    await renderReady()

    await userEvent.click(button())
    await vi.waitFor(() => expect(server.callsTo('GET', WATCHLIST)).toHaveLength(2))
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(button()).toBeDisabled()
    expect(button()).toHaveAttribute('aria-pressed', 'false')

    releaseList()

    await vi.waitFor(() => expect(button()).toBeEnabled())
    expect(button()).toHaveAttribute('aria-pressed', 'true')
  })

  it('처리 중에는 누를 수 없어서 두 번 눌러도 요청은 한 번이다', async () => {
    let respond: (value: MockResponse) => void = () => {}
    const server = setupServer({ put: () => new Promise((resolve) => (respond = resolve)) })
    await renderReady()

    await userEvent.click(button())
    await vi.waitFor(() => expect(button()).toBeDisabled())
    await userEvent.click(button())

    expect(server.callsTo('PUT', ITEM)).toHaveLength(1)
    respond({ status: 204 })
    await vi.waitFor(() => expect(button()).toBeEnabled())
    expect(server.callsTo('PUT', ITEM)).toHaveLength(1)
  })
})
