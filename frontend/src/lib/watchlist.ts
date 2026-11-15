import { ApiError } from '../api/client'

/** 추가·해제 실패 문구. 409는 50개 초과(서버 문구를 그대로), 그 밖은 다시 시도 안내 */
export function watchErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 409) {
    return error.problem?.detail ?? '관심단지는 최대 50개까지예요'
  }
  return '잠시 후 다시 시도해 주세요'
}
