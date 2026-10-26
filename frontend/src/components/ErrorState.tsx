import { Link } from 'react-router'
import { ApiError, isNotFound } from '../api/client'

type Props = {
  error: unknown
  /** 404일 때 보여줄 문구. 예: "단지를 찾을 수 없어요" */
  notFoundTitle?: string
  onRetry?: () => void
  /** 4xx일 때 "단지 검색으로 가기" 링크. 검색 페이지 안에서는 자기 자신을 가리키므로 끈다 */
  showSearchLink?: boolean
}

/**
 * - 404: "없음" 안내 + 검색으로 가는 링크
 * - 그 밖의 4xx: 요청 자체가 잘못된 것이라 다시 시도해도 같다 (shouldRetry와 같은 기준). 버튼 없이 안내만
 * - 5xx·네트워크 오류: 다시 시도 버튼
 */
export function ErrorState({ error, notFoundTitle = '찾는 정보가 없어요', onRetry, showSearchLink = true }: Props) {
  if (isNotFound(error)) {
    return <ClientErrorBox title={notFoundTitle} showSearchLink={showSearchLink} />
  }
  if (error instanceof ApiError && error.status < 500) {
    return (
      <ClientErrorBox
        title="요청을 처리할 수 없어요"
        description="주소나 입력값을 확인해 주세요."
        showSearchLink={showSearchLink}
      />
    )
  }
  return (
    <div role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-10 text-center">
      <p className="font-medium text-red-800">데이터를 불러오지 못했어요</p>
      <p className="mt-1 text-sm text-red-700">잠시 후 다시 시도해 주세요.</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-4 rounded-lg border border-red-300 bg-white px-4 py-2 text-sm font-medium text-red-800 hover:bg-red-100"
        >
          다시 시도
        </button>
      )}
    </div>
  )
}

function ClientErrorBox({
  title,
  description,
  showSearchLink,
}: {
  title: string
  description?: string
  showSearchLink: boolean
}) {
  return (
    <div role="alert" className="rounded-xl border border-slate-200 bg-white px-4 py-10 text-center">
      <p className="font-medium text-slate-800">{title}</p>
      {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
      {showSearchLink && (
        <Link to="/" className="mt-3 inline-block text-sm font-medium text-blue-700 underline-offset-2 hover:underline">
          단지 검색으로 가기
        </Link>
      )}
    </div>
  )
}
