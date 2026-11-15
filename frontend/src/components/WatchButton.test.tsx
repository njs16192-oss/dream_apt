import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Schemas } from '../api/client'
import { mockApi } from '../test/mockApi'
import { renderWithProviders } from '../test/render'
import { WatchButton } from './WatchButton'

// 이 파일은 표시 상태만 본다. 버튼을 누르는 테스트는 추가·해제 mutation이 생긴 뒤에 쓴다 (PR 9a 커밋 5)

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
