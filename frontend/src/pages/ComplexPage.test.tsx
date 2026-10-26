import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { Schemas } from '../api/client'
import { mockApi, type MockResponse } from '../test/mockApi'
import { renderWithProviders } from '../test/render'
import { ComplexPage } from './ComplexPage'

const APT_SEQ = '11710-2884'
const DETAIL = `/api/complexes/${APT_SEQ}`
const TRADES = `/api/complexes/${APT_SEQ}/trades`

function areaGroup(overrides: Partial<Schemas['AreaGroupSummary']> = {}): Schemas['AreaGroupSummary'] {
  return {
    areaGroup: 85,
    exclusiveAreas: [84.95, 84.99],
    tradeCount: 289,
    bulkCount: 0,
    lastDealDate: '2026-09-18',
    lastDealAmount: 245000,
    ...overrides,
  }
}

function complex(overrides: Partial<Schemas['ComplexDetail']> = {}): Schemas['ComplexDetail'] {
  return {
    aptSeq: APT_SEQ,
    name: '헬리오시티',
    sggCd: '11710',
    sggName: '송파구',
    umdName: '가락동',
    buildYear: 2018,
    roadAddress: '송파대로 345',
    tradeCount: 1352,
    bulkCount: 0,
    bulkRatio: 0,
    bulkDominant: false,
    areaGroups: [areaGroup()],
    ...overrides,
  }
}

function trade(overrides: Partial<Schemas['TradeItem']> = {}): Schemas['TradeItem'] {
  return {
    id: 1,
    dealDate: '2026-09-18',
    dealAmount: 245000,
    exclusiveArea: 84.99,
    areaGroup: 85,
    floor: 12,
    aptDong: '101',
    dealingGbn: '중개거래',
    buyerGbn: '개인',
    sellerGbn: '개인',
    registeredDate: '2026-10-02',
    bulk: false,
    ...overrides,
  }
}

function tradePage(content: Schemas['TradeItem'][], { page = 0, totalElements = content.length } = {}): MockResponse {
  return { body: { content, page, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) } }
}

type TradesRoute = MockResponse | ((url: URL) => MockResponse | Promise<MockResponse>)

const monthlyPath = (area: number) => `/api/complexes/${APT_SEQ}/areas/${area}/monthly`

function priceSeries(months: Schemas['MonthlyPrice'][]): MockResponse {
  return { body: { baseDate: '2026-10-25', provisionalFrom: '2026-09', from: null, to: '2026-10', months } }
}

const price = (month: string, medianAmount: number, count = 3): Schemas['MonthlyPrice'] => ({
  month,
  medianAmount,
  count,
  provisional: month >= '2026-09',
})

type Setup = {
  detail?: MockResponse
  trades?: TradesRoute
  /** 면적별 월별 가격. 정하지 않은 면적은 빈 시리즈 */
  monthly?: Record<number, MockResponse | (() => Promise<MockResponse>)>
}

function setup({ detail = { body: complex() }, trades = tradePage([]), monthly = {} }: Setup = {}) {
  const monthlyRoutes = Object.fromEntries(
    [59, 85, 115].map((area) => [monthlyPath(area), monthly[area] ?? priceSeries([])]),
  )
  return mockApi({ [DETAIL]: detail, [TRADES]: trades, ...monthlyRoutes })
}

/** 직접 끝내는 응답. 로딩 중 화면을 보려고 쓴다 */
function deferred() {
  let resolve!: (response: MockResponse) => void
  const promise = new Promise<MockResponse>((r) => (resolve = r))
  return { promise, resolve }
}

function renderPage(route = `/complexes/${APT_SEQ}`, entries?: string[]) {
  return renderWithProviders(
    <Routes>
      <Route path="/complexes/:aptSeq" element={<ComplexPage />} />
      <Route path="*" element={<p>다른 화면</p>} />
    </Routes>,
    { route, entries },
  )
}

const THREE_GROUPS = [
  areaGroup({ areaGroup: 59, exclusiveAreas: [59.96], tradeCount: 120, lastDealAmount: 180000, lastDealDate: '2026-08-02' }),
  areaGroup(),
  areaGroup({ areaGroup: 115, exclusiveAreas: [114.9, 115.3], tradeCount: 0, bulkCount: 14, lastDealAmount: null, lastDealDate: null }),
]
const location = () => screen.getByTestId('location').textContent
const areaNav = () => screen.getByRole('navigation', { name: '면적' })
const tab = (name: RegExp) => within(areaNav()).getByRole('link', { name })

describe('ComplexPage', () => {
  it('헤더에 단지명·구·동·준공연도·주소와 구 거래량 링크를 보여주고 문서 제목을 바꾼다', async () => {
    setup()
    renderPage()

    expect(await screen.findByRole('heading', { name: '헬리오시티' })).toBeInTheDocument()
    expect(screen.getByText('송파구 가락동 · 2018년 준공')).toBeInTheDocument()
    expect(screen.getByText('송파대로 345')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '송파구 거래량 보기 →' })).toHaveAttribute('href', '/regions/11710')
    expect(document.title).toBe('헬리오시티 · Dream APT')
  })

  it('동·준공연도·주소가 없으면 그 부분을 뺀다', async () => {
    setup({ detail: { body: complex({ umdName: null, buildYear: null, roadAddress: null }) } })
    renderPage()

    expect(await screen.findByText('송파구')).toBeInTheDocument()
    expect(screen.queryByText(/준공/)).not.toBeInTheDocument()
  })

  it('없는 단지면 404 안내와 검색 링크를 보여주고 거래 목록은 요청하지 않는다', async () => {
    const server = setup({ detail: { status: 404, body: { status: 404, detail: '단지 없음' } } })
    renderPage()

    expect(await screen.findByText('단지를 찾을 수 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '단지 검색으로 가기' })).toHaveAttribute('href', '/')
    expect(server.to(TRADES)).toHaveLength(0)
  })

  it('일괄 거래 위주 단지면 비율 안내를 보여준다', async () => {
    setup({ detail: { body: complex({ bulkDominant: true, bulkRatio: 0.926 }) } })
    renderPage()

    expect(await screen.findByRole('note')).toHaveTextContent('거래의 93%가 일괄 거래')
  })

  it('일괄 거래 위주가 아니면 안내가 없다', async () => {
    setup({ detail: { body: complex({ bulkRatio: 0.3 }) } })
    renderPage()

    await screen.findByRole('heading', { name: '헬리오시티' })
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })

  describe('면적 탭', () => {
    it('그룹마다 반올림 면적·실면적 범위·거래 수·최근 거래를 보여주고 선택된 탭을 표시한다', async () => {
      setup({ detail: { body: complex({ areaGroups: THREE_GROUPS }) } })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      expect(await screen.findByRole('navigation', { name: '면적' })).toBeInTheDocument()
      const selected = tab(/전용 85㎡/)
      expect(selected).toHaveAttribute('aria-current', 'page')
      expect(selected).toHaveTextContent('전용 85㎡ (25.7평)')
      expect(selected).toHaveTextContent('실면적 84.95–84.99㎡')
      expect(selected).toHaveTextContent('289건')
      expect(selected).toHaveTextContent('최근 24억 5,000만 · 2026.09.18')

      expect(tab(/전용 59㎡/)).not.toHaveAttribute('aria-current')
      expect(tab(/전용 59㎡/)).toHaveTextContent('실면적 59.96㎡')
      expect(tab(/전용 115㎡/)).toHaveTextContent('0건 · 일괄 14건')
      expect(tab(/전용 115㎡/)).toHaveTextContent('일반 거래 없음')
    })

    it.each(['', '?area=abc', '?area=84', '?area=200&page=3'])(
      '%s 이면 거래 수 최다 그룹으로 replace하고 page를 지운다',
      async (search) => {
        setup({ detail: { body: complex({ areaGroups: THREE_GROUPS }) } })
        renderPage(undefined, ['/', `/complexes/${APT_SEQ}${search}`])

        await waitFor(() => expect(location()).toBe(`/complexes/${APT_SEQ}?area=85`))
        expect(tab(/전용 85㎡/)).toHaveAttribute('aria-current', 'page')

        // replace라서 뒤로 가면 잘못된 주소가 아니라 이전 화면
        fireEvent.click(screen.getByTestId('history-back'))
        expect(location()).toBe('/')
      },
    )

    it('있는 그룹이면 거래 수가 적어도 그대로 둔다', async () => {
      setup({ detail: { body: complex({ areaGroups: THREE_GROUPS }) } })
      renderPage(`/complexes/${APT_SEQ}?area=115&page=2`)

      expect(await screen.findByRole('navigation', { name: '면적' })).toBeInTheDocument()
      expect(tab(/전용 115㎡/)).toHaveAttribute('aria-current', 'page')
      expect(location()).toBe(`/complexes/${APT_SEQ}?area=115&page=2`)
    })

    it('탭을 누르면 면적을 바꾸고 page를 지우며, 뒤로 가기로 이전 면적에 돌아간다', async () => {
      setup({ detail: { body: complex({ areaGroups: THREE_GROUPS }) } })
      renderPage(`/complexes/${APT_SEQ}?area=85&page=3`)

      await userEvent.click(await screen.findByRole('link', { name: /전용 59㎡/ }))
      expect(location()).toBe(`/complexes/${APT_SEQ}?area=59`)
      expect(tab(/전용 59㎡/)).toHaveAttribute('aria-current', 'page')

      fireEvent.click(screen.getByTestId('history-back'))
      expect(location()).toBe(`/complexes/${APT_SEQ}?area=85&page=3`)
    })

    it('면적 그룹이 없으면 탭 대신 안내를 보여주고 주소를 바꾸지 않는다', async () => {
      setup({ detail: { body: complex({ areaGroups: [], tradeCount: 0 }) } })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      expect(await screen.findByText('아직 거래 기록이 없어요')).toBeInTheDocument()
      expect(screen.queryByRole('navigation', { name: '면적' })).not.toBeInTheDocument()
      expect(location()).toBe(`/complexes/${APT_SEQ}?area=85`)
    })
  })

  describe('거래 목록', () => {
    const cards = () => within(screen.getByRole('list', { name: '거래 목록' })).getAllByRole('listitem')
    const rows = () => within(screen.getByRole('table')).getAllByRole('row').slice(1)
    const pageNav = () => screen.getByRole('navigation', { name: '페이지' })

    it('카드와 표에 금액·계약일·실면적·층·동·거래 유형·등기일을 보여준다', async () => {
      setup({ trades: tradePage([trade()]) })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      const [card] = await waitFor(cards)
      expect(card).toHaveTextContent('24억 5,000만')
      expect(card).toHaveTextContent('2026.09.18')
      expect(card).toHaveTextContent('84.99㎡ · 12층 · 101동')
      expect(card).toHaveTextContent('중개거래 · 등기 2026.10.02')

      const cells = within(rows()[0]).getAllByRole('cell').map((cell) => cell.textContent)
      expect(cells).toEqual(['2026.09.18', '24억 5,000만', '84.99㎡', '12층', '101동', '중개거래', '2026.10.02'])
      expect(screen.getByText('1건')).toBeInTheDocument()
    })

    it('일괄 거래에 배지를 붙이고, 빈 값은 등기일 필드가 생긴 2023-01 전후로 다르게 쓴다', async () => {
      setup({
        trades: tradePage([
          trade({ id: 1, bulk: true }),
          trade({ id: 2, dealDate: '2026-10-20', aptDong: null, registeredDate: null }),
          trade({ id: 3, dealDate: '2021-03-02', dealingGbn: null, registeredDate: null, floor: -1 }),
        ]),
      })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      const [bulk, pending, old] = await waitFor(cards)
      expect(within(bulk).getByText('일괄')).toBeInTheDocument()
      expect(within(pending).queryByText('일괄')).not.toBeInTheDocument()

      expect(pending).toHaveTextContent('84.99㎡ · 12층')
      expect(pending).not.toHaveTextContent('동')
      expect(pending).toHaveTextContent('중개거래 · 등기 전')
      // 2023-01 전 계약은 등기 여부를 알 수 없어 빼고, 거래 유형도 없다
      expect(old).toHaveTextContent('지하 1층')
      expect(old).not.toHaveTextContent('등기')
      expect(old).not.toHaveTextContent('중개거래')

      const cellsOf = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)
      expect(cellsOf(rows()[1]).slice(4)).toEqual(['-', '중개거래', '등기 전'])
      expect(cellsOf(rows()[2]).slice(3)).toEqual(['지하 1층', '101동', '-', '-'])
    })

    it('?page=3이면 API에 page=2로 요청하고, 이전/다음 링크를 둔다', async () => {
      const server = setup({ trades: tradePage([trade()], { page: 2, totalElements: 95 }) })
      renderPage(`/complexes/${APT_SEQ}?area=85&page=3`)

      await waitFor(cards)
      const [request] = server.to(TRADES)
      expect(Object.fromEntries(request.searchParams)).toEqual({ areaGroup: '85', page: '2', size: '20' })

      expect(pageNav()).toHaveTextContent('3 / 5')
      expect(within(pageNav()).getByRole('link', { name: '이전' })).toHaveAttribute('href', `/complexes/${APT_SEQ}?area=85&page=2`)
      expect(within(pageNav()).getByRole('link', { name: '다음' })).toHaveAttribute('href', `/complexes/${APT_SEQ}?area=85&page=4`)
    })

    it('첫 페이지는 이전이, 끝 페이지는 다음이 비활성이고, 2페이지의 이전은 page 없는 주소다', async () => {
      setup({ trades: (url) => tradePage([trade()], { page: Number(url.searchParams.get('page')), totalElements: 45 }) })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      await waitFor(cards)
      expect(within(pageNav()).queryByRole('link', { name: '이전' })).not.toBeInTheDocument()
      expect(within(pageNav()).getByText('이전')).toHaveAttribute('aria-disabled', 'true')

      await userEvent.click(within(pageNav()).getByRole('link', { name: '다음' }))
      await waitFor(() => expect(pageNav()).toHaveTextContent('2 / 3'))
      expect(within(pageNav()).getByRole('link', { name: '이전' })).toHaveAttribute('href', `/complexes/${APT_SEQ}?area=85`)

      await userEvent.click(within(pageNav()).getByRole('link', { name: '다음' }))
      await waitFor(() => expect(pageNav()).toHaveTextContent('3 / 3'))
      expect(within(pageNav()).getByText('다음')).toHaveAttribute('aria-disabled', 'true')
    })

    it('한 페이지뿐이면 페이지 이동을 숨긴다', async () => {
      setup({ trades: tradePage([trade()]) })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      await waitFor(cards)
      expect(screen.queryByRole('navigation', { name: '페이지' })).not.toBeInTheDocument()
    })

    it('범위 밖 페이지면 안내와 첫 페이지 링크를 보여준다', async () => {
      setup({ trades: tradePage([], { page: 9, totalElements: 45 }) })
      renderPage(`/complexes/${APT_SEQ}?area=85&page=10`)

      expect(await screen.findByText('이 페이지에 거래가 없어요')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: '첫 페이지로' })).toHaveAttribute('href', `/complexes/${APT_SEQ}?area=85`)
    })

    it('?area= 보정 전 주소로는 거래 목록을 요청하지 않는다', async () => {
      const server = setup({ detail: { body: complex({ areaGroups: THREE_GROUPS }) }, trades: tradePage([trade()]) })
      renderPage(`/complexes/${APT_SEQ}?area=abc&page=4`)

      await waitFor(cards)
      expect(server.to(TRADES).map((url) => url.search)).toEqual(['?areaGroup=85&page=0&size=20'])
    })

    it('면적을 바꾸면 응답이 올 때까지 이전 면적의 거래를 보여주지 않는다', async () => {
      const pending59 = deferred()
      setup({
        detail: { body: complex({ areaGroups: THREE_GROUPS }) },
        trades: (url) =>
          url.searchParams.get('areaGroup') === '59'
            ? pending59.promise
            : tradePage([trade({ dealAmount: 245000 })]),
      })
      renderPage(`/complexes/${APT_SEQ}?area=85`)
      await waitFor(cards)

      await userEvent.click(tab(/전용 59㎡/))

      expect(await screen.findByText('거래 불러오는 중')).toBeInTheDocument()
      expect(screen.queryByText('24억 5,000만')).not.toBeInTheDocument()

      pending59.resolve(tradePage([trade({ id: 9, dealAmount: 180000, areaGroup: 59, exclusiveArea: 59.96 })]))
      expect(await screen.findAllByText('18억')).not.toHaveLength(0)
    })

    it('같은 면적에서 페이지를 넘기면 응답이 올 때까지 이전 목록을 흐리게 남긴다', async () => {
      const pendingPage2 = deferred()
      setup({
        trades: (url) =>
          url.searchParams.get('page') === '1'
            ? pendingPage2.promise
            : tradePage([trade({ dealAmount: 245000 })], { totalElements: 45 }),
      })
      renderPage(`/complexes/${APT_SEQ}?area=85`)
      await waitFor(cards)

      await userEvent.click(within(pageNav()).getByRole('link', { name: '다음' }))

      expect(location()).toBe(`/complexes/${APT_SEQ}?area=85&page=2`)
      expect(screen.queryByText('거래 불러오는 중')).not.toBeInTheDocument()
      expect(cards()[0]).toHaveTextContent('24억 5,000만')
      expect(screen.getByRole('list', { name: '거래 목록' }).parentElement).toHaveClass('opacity-50')

      pendingPage2.resolve(tradePage([trade({ id: 21, dealAmount: 230000 })], { page: 1, totalElements: 45 }))
      await waitFor(() => expect(cards()[0]).toHaveTextContent('23억'))
    })

    it('거래 목록만 실패하면 그 자리에 오류를 보여주고 헤더·탭은 남긴다', async () => {
      setup({ trades: { status: 500, body: { status: 500 } } })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      expect(await screen.findByText('데이터를 불러오지 못했어요')).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: '헬리오시티' })).toBeInTheDocument()
      expect(screen.getByRole('navigation', { name: '면적' })).toBeInTheDocument()
    })
  })

  describe('가격 추이', () => {
    const PRICES = [price('2022-03', 150000), price('2024-06', 200000), price('2026-08', 245000, 12), price('2026-10', 250000, 2)]
    /** 표 보기를 열고 계약월 열을 읽는다 */
    const tableMonths = async () => {
      const details = (await screen.findByText('표로 보기')).closest('details')!
      details.open = true
      const table = within(details).getByRole('table', { name: '월별 가격' })
      return within(table)
        .getAllByRole('row')
        .slice(1)
        .map((row) => within(row).getAllByRole('cell')[0].textContent)
    }
    const periodButton = (name: string) => within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name })

    it('기본 3년: 표 보기에 기간 안의 달을 최신순으로, 집계 중 달을 표시한다', async () => {
      const server = setup({ monthly: { 85: priceSeries(PRICES) } })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      expect(await tableMonths()).toEqual(['2026.10집계 중', '2026.08', '2024.06'])
      expect(periodButton('3년')).toHaveAttribute('aria-pressed', 'true')
      expect(screen.getByText(/집계 중 \(최근 2개월/)).toBeInTheDocument()
      expect(server.to(monthlyPath(85))).toHaveLength(1)
      // 기간 없이 전체를 받는다
      expect(server.to(monthlyPath(85))[0].search).toBe('')
    })

    it('기간을 바꾸면 다시 요청하지 않고 화면에서 자르며, 주소에 period를 replace로 남긴다', async () => {
      const server = setup({ monthly: { 85: priceSeries(PRICES) } })
      renderPage(undefined, ['/', `/complexes/${APT_SEQ}?area=85`])
      await tableMonths()

      await userEvent.click(periodButton('1년'))
      expect(await tableMonths()).toEqual(['2026.10집계 중', '2026.08'])
      expect(location()).toBe(`/complexes/${APT_SEQ}?area=85&period=1y`)

      await userEvent.click(periodButton('전체'))
      expect(await tableMonths()).toEqual(['2026.10집계 중', '2026.08', '2024.06', '2022.03'])

      await userEvent.click(periodButton('5년'))
      await userEvent.click(periodButton('3년'))
      expect(location()).toBe(`/complexes/${APT_SEQ}?area=85`)
      expect(server.to(monthlyPath(85))).toHaveLength(1)

      fireEvent.click(screen.getByTestId('history-back'))
      expect(location()).toBe('/')
    })

    it('면적 탭과 페이지 링크는 기간을 유지한다', async () => {
      setup({
        detail: { body: complex({ areaGroups: THREE_GROUPS }) },
        trades: tradePage([trade()], { totalElements: 45 }),
      })
      renderPage(`/complexes/${APT_SEQ}?area=85&period=1y`)

      const next = await within(await screen.findByRole('navigation', { name: '페이지' })).findByRole('link', { name: '다음' })
      expect(next).toHaveAttribute('href', `/complexes/${APT_SEQ}?area=85&period=1y&page=2`)

      await userEvent.click(tab(/전용 59㎡/))
      expect(location()).toBe(`/complexes/${APT_SEQ}?area=59&period=1y`)
      expect(periodButton('1년')).toHaveAttribute('aria-pressed', 'true')
    })

    it('선택 기간에 거래가 없으면 기간을 늘리라고 안내한다', async () => {
      setup({ monthly: { 85: priceSeries([price('2022-03', 150000)]) } })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      expect(await screen.findByText('이 기간에는 거래가 없어요')).toBeInTheDocument()
      await userEvent.click(periodButton('전체'))
      expect(await tableMonths()).toEqual(['2022.03'])
    })

    it('일괄 거래 위주 단지는 가격 추이 대신 안내하고 요청하지 않는다', async () => {
      const server = setup({ detail: { body: complex({ bulkDominant: true, bulkRatio: 0.9 }) } })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      expect(await screen.findByText('일괄 거래 위주라 가격 추이를 보여주지 않아요')).toBeInTheDocument()
      expect(screen.queryByRole('group', { name: '기간' })).not.toBeInTheDocument()
      expect(server.to(monthlyPath(85))).toHaveLength(0)
    })

    it('일반 거래가 없는 면적은 안내하고 요청하지 않는다', async () => {
      const server = setup({ detail: { body: complex({ areaGroups: THREE_GROUPS }) } })
      renderPage(`/complexes/${APT_SEQ}?area=115`)

      expect(await screen.findByText('이 면적은 일반 거래가 없어요')).toBeInTheDocument()
      expect(server.to(monthlyPath(115))).toHaveLength(0)
    })

    it('면적을 바꾸면 응답이 올 때까지 이전 면적의 가격을 보여주지 않는다', async () => {
      const pending59 = deferred()
      setup({
        detail: { body: complex({ areaGroups: THREE_GROUPS }) },
        monthly: { 85: priceSeries(PRICES), 59: () => pending59.promise },
      })
      renderPage(`/complexes/${APT_SEQ}?area=85`)
      await tableMonths()

      await userEvent.click(tab(/전용 59㎡/))

      expect(await screen.findByText('가격 불러오는 중')).toBeInTheDocument()
      expect(screen.queryByRole('table', { name: '월별 가격' })).not.toBeInTheDocument()

      pending59.resolve(priceSeries([price('2026-05', 180000)]))
      expect(await tableMonths()).toEqual(['2026.05'])
    })

    it('가격만 실패하면 그 자리에 오류를 보여주고 거래 목록은 남긴다', async () => {
      setup({ monthly: { 85: { status: 500, body: { status: 500 } } }, trades: tradePage([trade()]) })
      renderPage(`/complexes/${APT_SEQ}?area=85`)

      expect(await screen.findByText('데이터를 불러오지 못했어요')).toBeInTheDocument()
      expect(await screen.findByRole('list', { name: '거래 목록' })).toBeInTheDocument()
    })
  })
})
