import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Schemas } from '../api/client'
import { mockApi, type MockResponse } from '../test/mockApi'
import { renderWithProviders } from '../test/render'
import { RegionPage } from './RegionPage'

const MONTHLY = '/api/regions/11710/monthly'
const REGIONS: Schemas['RegionItem'][] = [{ sggCd: '11710', sidoName: '서울특별시', sggName: '송파구' }]

const volume = (month: string, count: number, bulkCount = 0): Schemas['MonthlyVolume'] => ({
  month,
  count,
  bulkCount,
  provisional: month >= '2026-09',
})

function volumeSeries(months: Schemas['MonthlyVolume'][]): MockResponse {
  return { body: { baseDate: '2026-10-25', provisionalFrom: '2026-09', from: '2016-11', to: '2026-10', months } }
}

function setup(monthly: MockResponse = volumeSeries([])) {
  return mockApi({ '/api/regions': { body: REGIONS }, [MONTHLY]: monthly })
}

function renderPage(route = '/regions/11710') {
  return renderWithProviders(
    <Routes>
      <Route path="/regions/:sggCd" element={<RegionPage />} />
    </Routes>,
    { route },
  )
}

/** 표 보기를 열고 [계약월, 일반, 일괄] 행을 읽는다 */
async function tableRows() {
  const details = (await screen.findByText('표로 보기')).closest('details')!
  details.open = true
  const table = within(details).getByRole('table', { name: '월별 거래량' })
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent))
}

const periodButton = (name: string) => within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name })

describe('RegionPage', () => {
  beforeEach(() => {
    // 서울 2026-10-26 10:00
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-26T01:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('서울 기준 이번 달까지 120개월을 from·to 둘 다 보내 한 번만 요청한다', async () => {
    const server = setup()
    renderPage()

    expect(await screen.findByRole('heading', { name: '송파구 거래량' })).toBeInTheDocument()
    await screen.findByText('이 기간에는 거래가 없어요')
    expect(server.to(MONTHLY)).toHaveLength(1)
    expect(Object.fromEntries(server.to(MONTHLY)[0].searchParams)).toEqual({ from: '2016-11', to: '2026-10' })
  })

  it('구 이름으로 제목·문서 제목·단지 검색 링크를 만든다', async () => {
    setup()
    renderPage()

    expect(await screen.findByRole('heading', { name: '송파구 거래량' })).toBeInTheDocument()
    expect(document.title).toBe('송파구 거래량 · Dream APT')
    expect(screen.getByRole('link', { name: '송파구 단지 검색 →' })).toHaveAttribute('href', '/?sggCd=11710')
  })

  it('표 보기에 일반·일괄을 나눠 보여주고, 응답에 없는 달은 0건으로 채운다', async () => {
    setup(volumeSeries([volume('2026-07', 412, 12), volume('2026-10', 30)]))
    renderPage('/regions/11710?period=1y')

    const rows = await tableRows()
    expect(rows.slice(0, 4)).toEqual([
      ['2026.10집계 중', '30건', '0건'],
      ['2026.09집계 중', '0건', '0건'],
      ['2026.08', '0건', '0건'],
      ['2026.07', '412건', '12건'],
    ])
    expect(rows).toHaveLength(12)
  })

  it('기간을 바꾸면 다시 요청하지 않고 화면에서 자른다', async () => {
    const server = setup(volumeSeries([volume('2019-03', 5), volume('2026-07', 412)]))
    renderPage()

    expect(await tableRows()).toHaveLength(36)
    await userEvent.click(periodButton('1년'))
    expect(await tableRows()).toHaveLength(12)
    await userEvent.click(periodButton('전체'))
    // 전체는 첫 데이터 달(2019-03)부터
    const all = await tableRows()
    expect(all.at(-1)).toEqual(['2019.03', '5건', '0건'])
    expect(server.to(MONTHLY)).toHaveLength(1)
  })

  it('없는 구면 404 안내를 보여준다', async () => {
    setup({ status: 404, body: { status: 404, detail: '구가 없습니다' } })
    renderPage('/regions/99999')

    expect(await screen.findByText('구를 찾을 수 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '단지 검색으로 가기' })).toBeInTheDocument()
  })

  it('구 목록을 못 불러와도 코드로 제목을 보여준다', async () => {
    mockApi({ '/api/regions': { status: 500, body: {} }, [MONTHLY]: volumeSeries([volume('2026-07', 1)]) })
    renderPage()

    expect(await screen.findByRole('heading', { name: '11710 거래량' })).toBeInTheDocument()
  })
})
