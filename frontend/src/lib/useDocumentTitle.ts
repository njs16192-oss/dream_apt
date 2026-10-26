import { useEffect } from 'react'

const APP_NAME = 'Dream APT'

/**
 * 브라우저 탭·히스토리에 보이는 제목. "헬리오시티 · Dream APT"
 * title이 없으면(불러오는 중 등) 앱 이름만. 화면을 떠나면 앱 이름으로 되돌려, 제목을 정하지 않은 화면에 이전 제목이 남지 않게 한다.
 */
export function useDocumentTitle(title: string | null | undefined) {
  useEffect(() => {
    document.title = title ? `${title} · ${APP_NAME}` : APP_NAME
  }, [title])

  useEffect(
    () => () => {
      document.title = APP_NAME
    },
    [],
  )
}
