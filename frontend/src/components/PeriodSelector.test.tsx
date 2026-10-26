import { fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { usePeriodParam } from '../lib/usePeriodParam'
import { renderWithProviders } from '../test/render'
import { PeriodSelector } from './PeriodSelector'

function Harness() {
  const [period, setPeriod] = usePeriodParam()
  return <PeriodSelector value={period} onChange={setPeriod} />
}

const location = () => screen.getByTestId('location').textContent
const button = (name: string) => screen.getByRole('button', { name })

describe('PeriodSelector + usePeriodParam', () => {
  it('기본은 3년, 잘못된 값도 3년', () => {
    renderWithProviders(<Harness />, { route: '/complexes/1?period=2y' })
    expect(button('3년')).toHaveAttribute('aria-pressed', 'true')
    expect(button('1년')).toHaveAttribute('aria-pressed', 'false')
  })

  it('기간을 바꾸면 다른 파라미터를 유지한 채 replace하고, 기본값이면 주소에서 뺀다', async () => {
    renderWithProviders(<Harness />, { entries: ['/', '/complexes/1?area=85&page=2'] })

    await userEvent.click(button('1년'))
    expect(location()).toBe('/complexes/1?area=85&page=2&period=1y')
    expect(button('1년')).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(button('3년'))
    expect(location()).toBe('/complexes/1?area=85&page=2')

    // replace라서 뒤로 가면 기간 변경 전이 아니라 이전 화면
    fireEvent.click(screen.getByTestId('history-back'))
    expect(location()).toBe('/')
  })
})
