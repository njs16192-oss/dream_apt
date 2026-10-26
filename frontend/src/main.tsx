import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { AppRoutes } from './app/AppRoutes'
import { createQueryClient } from './app/queryClient'
// 글자 범위별로 나눈 woff2 조각(unicode-range)이라 화면에 쓰인 글자의 조각만 내려받는다
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import './index.css'

const queryClient = createQueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
