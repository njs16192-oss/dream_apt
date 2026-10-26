import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { renderWithProviders } from '../test/render'
import { ErrorState } from './ErrorState'

describe('ErrorState', () => {
  it('404는 지정한 문구와 검색 링크, 다시 시도 버튼 없음', () => {
    renderWithProviders(<ErrorState error={new ApiError(404)} notFoundTitle="단지를 찾을 수 없어요" onRetry={vi.fn()} />)

    expect(screen.getByText('단지를 찾을 수 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '단지 검색으로 가기' })).toHaveAttribute('href', '/')
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('404 외 4xx는 요청 오류 안내, 다시 시도 버튼 없음', () => {
    renderWithProviders(<ErrorState error={new ApiError(400, { detail: 'Validation failure' })} onRetry={vi.fn()} />)

    expect(screen.getByText('요청을 처리할 수 없어요')).toBeInTheDocument()
    expect(screen.queryByText(/잠시 후 다시 시도/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it.each([
    ['5xx', new ApiError(500)],
    ['네트워크 오류', new TypeError('Failed to fetch')],
  ])('%s는 다시 시도 버튼을 누르면 onRetry를 부른다', async (_, error) => {
    const onRetry = vi.fn()
    renderWithProviders(<ErrorState error={error} onRetry={onRetry} />)

    expect(screen.getByText('데이터를 불러오지 못했어요')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
