import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { Schemas } from '../api/client'
import { mockApi, type MockResponse } from '../test/mockApi'
import { renderWithProviders } from '../test/render'
import { SearchPage } from './SearchPage'

const REGIONS: Schemas['RegionItem'][] = [
  { sggCd: '11680', sidoName: '서울특별시', sggName: '강남구' },
  { sggCd: '11710', sidoName: '서울특별시', sggName: '송파구' },
]

function complex(overrides: Partial<Schemas['ComplexSearchItem']> = {}): Schemas['ComplexSearchItem'] {
  return {
    aptSeq: '11710-2884',
    name: '헬리오시티',
    sggCd: '11710',
    sggName: '송파구',
    umdName: '가락동',
    buildYear: 2018,
    recentTradeCount: 412,
    ...overrides,
  }
}

/** 검색어별 결과. 정해지지 않은 검색어는 0건 */
function setup(results: Record<string, Schemas['ComplexSearchItem'][]> = {}, complexes?: (url: URL) => MockResponse) {
  return mockApi({
    '/api/regions': { body: REGIONS },
    '/api/complexes': complexes ?? ((url) => ({ body: results[url.searchParams.get('q') ?? ''] ?? [] })),
  })
}

const SEARCH = '/api/complexes'
const searchBox = () => screen.getByRole('searchbox', { name: '단지명' })
const regionSelect = () => screen.getByRole('combobox', { name: '구' })
/** 현재 URL의 검색 파라미터 (디코딩된 값으로 비교하려고) */
const urlParams = () => new URLSearchParams(screen.getByTestId('location').textContent!.split('?')[1] ?? '')
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

describe('SearchPage', () => {
  it('입력 전에는 안내를 보여주고 검색 요청을 하지 않는다', async () => {
    const server = setup()
    renderWithProviders(<SearchPage />)

    expect(screen.getByText('단지 이름으로 검색하세요')).toBeInTheDocument()
    await wait(400)
    expect(server.to(SEARCH)).toHaveLength(0)
  })

  it('여러 글자를 이어 쳐도 debounce가 끝난 뒤 한 번만 요청한다', async () => {
    const server = setup({ 헬리오: [complex()] })
    renderWithProviders(<SearchPage />)

    await userEvent.type(searchBox(), '헬리오')

    expect(await screen.findByText('헬리오시티')).toBeInTheDocument()
    expect(server.to(SEARCH)).toHaveLength(1)
    expect(server.to(SEARCH)[0].searchParams.get('q')).toBe('헬리오')
    expect(server.to(SEARCH)[0].searchParams.get('limit')).toBe('20')
    expect(urlParams().get('q')).toBe('헬리오')
  })

  it('결과 카드에 구·동·준공연도·최근 12개월 거래 수와 상세 링크를 보여준다', async () => {
    setup({ 헬리오: [complex(), complex({ aptSeq: '11710-9', name: '헬리오 2차', umdName: null, buildYear: null, recentTradeCount: 0 })] })
    renderWithProviders(<SearchPage />, { route: '/?q=헬리오' })

    const first = (await screen.findByText('헬리오시티')).closest('a')!
    expect(first).toHaveAttribute('href', '/complexes/11710-2884')
    expect(within(first).getByText('송파구 가락동 · 2018년 준공')).toBeInTheDocument()
    expect(within(first).getByText('412건')).toBeInTheDocument()

    const second = screen.getByText('헬리오 2차').closest('a')!
    expect(within(second).getByText('송파구')).toBeInTheDocument()
    expect(within(second).queryByText(/준공/)).not.toBeInTheDocument()
    expect(screen.getByText(/일괄 거래.*를 뺀 수/)).toBeInTheDocument()
  })

  it('자모만 입력한 상태(한글 조합 중)로 멈추면 요청하지 않고 안내를 유지한다', async () => {
    const server = setup()
    renderWithProviders(<SearchPage />)

    await userEvent.type(searchBox(), 'ㄹ')
    await wait(400)

    expect(server.to(SEARCH)).toHaveLength(0)
    expect(screen.getByText('단지 이름으로 검색하세요')).toBeInTheDocument()
    expect(screen.queryByText(/검색 결과가 없어요/)).not.toBeInTheDocument()
  })

  it('끝 글자를 조합하는 중에 멈추면 자모를 뗀 검색어로 요청하고, 같은 캐시를 쓴다', async () => {
    const server = setup({ 롯: [complex({ name: '롯데캐슬' })] })
    renderWithProviders(<SearchPage />)

    await userEvent.type(searchBox(), '롯')
    expect(await screen.findByText('롯데캐슬')).toBeInTheDocument()
    await userEvent.type(searchBox(), 'ㄷ')
    await wait(400)

    expect(server.to(SEARCH).map((url) => url.searchParams.get('q'))).toEqual(['롯'])
    expect(screen.getByText('롯데캐슬')).toBeInTheDocument()
    expect(screen.queryByText(/검색 결과가 없어요/)).not.toBeInTheDocument()
  })

  it('URL로 들어오면 입력값과 구 선택을 복원하고 바로 검색한다', async () => {
    const server = setup({ 헬리오: [complex()] })
    renderWithProviders(<SearchPage />, { route: '/?q=헬리오&sggCd=11710' })

    expect(searchBox()).toHaveValue('헬리오')
    expect(await screen.findByText('헬리오시티')).toBeInTheDocument()
    await waitFor(() => expect(regionSelect()).toHaveValue('11710'))
    expect(server.to(SEARCH)).toHaveLength(1)
    expect(server.to(SEARCH)[0].searchParams.get('sggCd')).toBe('11710')
  })

  it('뒤로 가기로 URL이 바뀌면 입력값도 URL 값으로 바뀐다', async () => {
    const server = setup({ 롯데: [complex({ aptSeq: 'L', name: '롯데캐슬' })], 헬리오: [complex()] })
    renderWithProviders(<SearchPage />, { entries: ['/?q=롯데', '/?q=헬리오'] })

    expect(await screen.findByText('헬리오시티')).toBeInTheDocument()
    fireEvent.click(screen.getByTestId('history-back'))

    await waitFor(() => expect(searchBox()).toHaveValue('롯데'))
    expect(await screen.findByText('롯데캐슬')).toBeInTheDocument()
    await wait(400)
    // 입력값이 바뀐 뒤 debounce가 끝나도 URL을 다시 쓰지 않는다 (루프 없음)
    expect(urlParams().get('q')).toBe('롯데')
    expect(server.to(SEARCH).map((url) => url.searchParams.get('q'))).toEqual(['헬리오', '롯데'])
  })

  it('이 화면이 URL에 쓴 값은 입력창으로 되돌리지 않는다 (입력 → URL → 입력 루프 없음)', async () => {
    setup({ 래미안: [complex({ name: '래미안' })], '래미안 대치': [complex({ name: '래미안 대치팰리스' })] })
    renderWithProviders(<SearchPage />)

    // URL에는 trim한 "래미안"이 쓰인다. 그 값을 입력창에 되돌리면 사용자가 친 공백이 사라진다
    await userEvent.type(searchBox(), '래미안 ')
    await waitFor(() => expect(urlParams().get('q')).toBe('래미안'))
    await wait(100)
    expect(searchBox()).toHaveValue('래미안 ')

    await userEvent.type(searchBox(), '대치')
    await waitFor(() => expect(urlParams().get('q')).toBe('래미안 대치'))
    expect(searchBox()).toHaveValue('래미안 대치')
  })

  it('URL 반영 뒤 이어 친 글자도 유지된다', async () => {
    setup({ 헬리오: [complex()], 헬리오시티: [complex()] })
    renderWithProviders(<SearchPage />)

    await userEvent.type(searchBox(), '헬리오')
    await waitFor(() => expect(urlParams().get('q')).toBe('헬리오'))
    await userEvent.type(searchBox(), '시티')

    expect(searchBox()).toHaveValue('헬리오시티')
    await waitFor(() => expect(urlParams().get('q')).toBe('헬리오시티'))
    expect(searchBox()).toHaveValue('헬리오시티')
  })

  it('입력을 지우면 이전 결과 대신 바로 안내를 보여준다', async () => {
    setup({ 헬리오: [complex()] })
    renderWithProviders(<SearchPage />, { route: '/?q=헬리오' })
    expect(await screen.findByText('헬리오시티')).toBeInTheDocument()

    await userEvent.clear(searchBox())

    expect(screen.queryByText('헬리오시티')).not.toBeInTheDocument()
    expect(screen.getByText('단지 이름으로 검색하세요')).toBeInTheDocument()
  })

  it('앞뒤 공백만 다른 검색어는 같은 캐시를 써서 다시 요청하지 않는다', async () => {
    const server = setup({ 헬리오: [complex()] })
    renderWithProviders(<SearchPage />)

    await userEvent.type(searchBox(), '헬리오')
    expect(await screen.findByText('헬리오시티')).toBeInTheDocument()
    await userEvent.type(searchBox(), ' ')
    await wait(400)

    expect(server.to(SEARCH)).toHaveLength(1)
    expect(screen.getByText('헬리오시티')).toBeInTheDocument()
  })

  it('구를 고르면 요청에 sggCd가 붙고 구 거래량 링크가 나온다', async () => {
    const server = setup({ 헬리오: [complex()] })
    renderWithProviders(<SearchPage />, { route: '/?q=헬리오' })
    expect(await screen.findByText('헬리오시티')).toBeInTheDocument()
    await waitFor(() => expect(regionSelect()).toBeEnabled())

    await userEvent.selectOptions(regionSelect(), '송파구')

    expect(screen.getByRole('link', { name: '송파구 거래량 보기 →' })).toHaveAttribute('href', '/regions/11710')
    await waitFor(() => expect(server.to(SEARCH)).toHaveLength(2))
    expect(server.to(SEARCH)[1].searchParams.get('sggCd')).toBe('11710')
    expect(urlParams().get('sggCd')).toBe('11710')
  })

  it('구 목록을 못 불러와도 구 선택만 막히고 검색은 된다', async () => {
    mockApi({
      '/api/regions': { status: 500, body: {} },
      '/api/complexes': { body: [complex()] },
    })
    renderWithProviders(<SearchPage />, { route: '/?q=헬리오' })

    expect(await screen.findByText('헬리오시티')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('option', { name: '구 목록을 못 불러옴' })).toBeInTheDocument())
    expect(regionSelect()).toBeDisabled()
  })

  it.each([
    [20, true],
    [19, false],
  ])('결과가 %i건이면 검색어를 좁히라는 안내 표시: %s', async (count, shown) => {
    const items = Array.from({ length: count }, (_, i) => complex({ aptSeq: `A-${i}`, name: `래미안 ${i}` }))
    setup({ 래미안: items })
    renderWithProviders(<SearchPage />, { route: '/?q=래미안' })

    expect(await screen.findByText('래미안 0')).toBeInTheDocument()
    expect(screen.queryByText(/검색어를 더 구체적으로/) !== null).toBe(shown)
  })

  it('결과가 0건이면 빈 상태, 구 필터 중이면 필터를 풀라고 안내한다', async () => {
    setup()
    renderWithProviders(<SearchPage />, { route: '/?q=없는단지&sggCd=11710' })

    expect(await screen.findByText("'없는단지' 검색 결과가 없어요")).toBeInTheDocument()
    expect(await screen.findByText(/구 필터를 풀어 보세요/)).toBeInTheDocument()
  })

  it('서버 오류면 다시 시도 버튼을 보여주고, 누르면 다시 요청한다', async () => {
    let calls = 0
    const server = setup({}, () => {
      calls++
      return calls <= 2 ? { status: 500, body: { status: 500 } } : { body: [complex()] }
    })
    renderWithProviders(<SearchPage />, { route: '/?q=헬리오' })

    // 5xx는 한 번 재시도한 뒤 오류 표시
    expect(await screen.findByText('데이터를 불러오지 못했어요')).toBeInTheDocument()
    expect(server.to(SEARCH)).toHaveLength(2)

    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('헬리오시티')).toBeInTheDocument()
    expect(server.to(SEARCH)).toHaveLength(3)
  })

  it('400이면 재시도하지 않고 다시 시도 버튼도 없다', async () => {
    const server = setup({}, () => ({ status: 400, body: { status: 400, detail: 'Validation failure' } }))
    renderWithProviders(<SearchPage />, { route: '/?q=헬리오' })

    expect(await screen.findByText('요청을 처리할 수 없어요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
    // 지금 페이지가 검색이라 "단지 검색으로 가기"는 자기 자신을 가리킨다
    expect(screen.queryByRole('link', { name: '단지 검색으로 가기' })).not.toBeInTheDocument()
    expect(server.to(SEARCH)).toHaveLength(1)
  })
})
