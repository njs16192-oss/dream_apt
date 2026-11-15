import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { watchErrorMessage } from './watchlist'

describe('watchErrorMessage', () => {
  it('409는 서버 문구를 그대로 쓴다', () => {
    expect(watchErrorMessage(new ApiError(409, { status: 409, detail: '관심단지는 최대 50개까지입니다' }))).toBe(
      '관심단지는 최대 50개까지입니다',
    )
  })

  it('409인데 서버 문구가 없으면 기본 문구', () => {
    expect(watchErrorMessage(new ApiError(409))).toBe('관심단지는 최대 50개까지예요')
  })

  it.each([[new ApiError(404)], [new ApiError(500)], [new TypeError('Failed to fetch')]])(
    '그 밖의 오류는 다시 시도 안내 (%s)',
    (error) => {
      expect(watchErrorMessage(error)).toBe('잠시 후 다시 시도해 주세요')
    },
  )
})
