import { Link } from 'react-router'
import { isNotFound } from '../api/client'

type Props = {
  error: unknown
  /** 404일 때 보여줄 문구. 예: "단지를 찾을 수 없어요" */
  notFoundTitle?: string
  onRetry?: () => void
}

/** 404는 "없음" 안내와 검색으로 가는 링크, 그 밖의 오류는 서버 오류 안내와 다시 시도 버튼. */
export function ErrorState({ error, notFoundTitle = '찾는 정보가 없어요', onRetry }: Props) {
  if (isNotFound(error)) {
    return (
      <div role="alert" className="rounded-xl border border-slate-200 bg-white px-4 py-10 text-center">
        <p className="font-medium text-slate-800">{notFoundTitle}</p>
        <Link to="/" className="mt-3 inline-block text-sm font-medium text-blue-700 underline-offset-2 hover:underline">
          단지 검색으로 가기
        </Link>
      </div>
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
