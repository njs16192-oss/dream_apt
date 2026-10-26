import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useDocumentTitle } from './useDocumentTitle'

describe('useDocumentTitle', () => {
  it('제목 뒤에 앱 이름을 붙이고, 제목이 바뀌면 따라간다', () => {
    const { rerender } = renderHook(({ title }) => useDocumentTitle(title), { initialProps: { title: '헬리오시티' } })
    expect(document.title).toBe('헬리오시티 · Dream APT')

    rerender({ title: '잠실엘스' })
    expect(document.title).toBe('잠실엘스 · Dream APT')
  })

  it('제목이 없으면 앱 이름만, 화면을 떠나면 앱 이름으로 되돌린다', () => {
    const { rerender, unmount } = renderHook(({ title }) => useDocumentTitle(title), {
      initialProps: { title: null as string | null },
    })
    expect(document.title).toBe('Dream APT')

    rerender({ title: '헬리오시티' })
    unmount()
    expect(document.title).toBe('Dream APT')
  })
})
