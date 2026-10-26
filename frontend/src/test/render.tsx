import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router'
import { createQueryClient } from '../app/queryClient'
import { RouterProbe } from './RouterProbe'

type Options = {
  /** 시작 주소. history를 여러 개 두려면 entries를 쓴다 */
  route?: string
  entries?: string[]
}

/**
 * 테스트용: 실제와 같은 QueryClient 설정 + 주어진 주소에서 시작하는 라우터.
 * 재시도 정책은 그대로 두고 재시도 간격만 0으로 줄인다 (기본 1초면 findBy 대기 시간을 넘는다).
 */
export function renderWithProviders(ui: ReactElement, { route = '/', entries }: Options = {}) {
  const queryClient = createQueryClient()
  queryClient.setDefaultOptions({
    queries: { ...queryClient.getDefaultOptions().queries, retryDelay: 0 },
  })
  const initialEntries = entries ?? [route]
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries} initialIndex={initialEntries.length - 1}>
        {ui}
        <RouterProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
