import { screen } from '@testing-library/react'
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

function renderPage(route = `/complexes/${APT_SEQ}`) {
  return renderWithProviders(
    <Routes>
      <Route path="/complexes/:aptSeq" element={<ComplexPage />} />
    </Routes>,
    { route },
  )
}

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
})
