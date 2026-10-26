import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/render'
import { ScrollToTop } from './ScrollToTop'

function setup(route: string) {
  const scrollTo = vi.fn()
  vi.stubGlobal('scrollTo', scrollTo)
  renderWithProviders(
    <>
      <ScrollToTop />
      <Link to="/complexes/1?area=85">상세</Link>
      <Link to="/complexes/1?area=59">다른 면적</Link>
      <Link to="/complexes/1?area=59&page=2">다음 페이지</Link>
    </>,
    { route },
  )
  scrollTo.mockClear()
  return scrollTo
}

describe('ScrollToTop', () => {
  it('다른 경로로 이동하면 맨 위로 올린다', async () => {
    const scrollTo = setup('/?q=헬리오')

    await userEvent.click(screen.getByRole('link', { name: '상세' }))

    expect(scrollTo).toHaveBeenCalledExactlyOnceWith(0, 0)
  })

  it('쿼리만 바뀌면 스크롤을 건드리지 않는다', async () => {
    const scrollTo = setup('/complexes/1?area=85')

    await userEvent.click(screen.getByRole('link', { name: '다른 면적' }))
    await userEvent.click(screen.getByRole('link', { name: '다음 페이지' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/complexes/1?area=59&page=2')
    expect(scrollTo).not.toHaveBeenCalled()
  })
})
