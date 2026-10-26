import { Link } from 'react-router'
import { EmptyState } from '../components/EmptyState'

export function NotFoundPage() {
  return (
    <EmptyState title="페이지를 찾을 수 없어요">
      <Link to="/" className="font-medium text-blue-700 underline-offset-2 hover:underline">
        단지 검색으로 가기
      </Link>
    </EmptyState>
  )
}
