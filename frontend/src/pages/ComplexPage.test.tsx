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

function setup({ detail = { body: complex() } }: { detail?: MockResponse } = {}) {
  return mockApi({
    [DETAIL]: detail,
    [TRADES]: { body: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } },
  })
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
})
