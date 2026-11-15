import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import {
  authErrorMessage,
  DUPLICATE_EMAIL_MESSAGE,
  hasErrors,
  LOGIN_FAILED_MESSAGE,
  utf8Bytes,
  validateLogin,
  validateSignup,
} from './authForm'

const valid = { email: 'user@example.com', password: 'password123', passwordConfirm: 'password123' }

describe('validateLogin', () => {
  it('둘 다 있으면 오류 없음. 이메일 형식·비밀번호 길이는 보지 않는다', () => {
    expect(validateLogin({ email: 'not-an-email', password: 'short' })).toEqual({})
  })

  it('비었거나 공백뿐이면 필드마다 오류', () => {
    const errors = validateLogin({ email: '  ', password: '' })
    expect(errors).toEqual({ email: '이메일을 입력해 주세요', password: '비밀번호를 입력해 주세요' })
    expect(hasErrors(errors)).toBe(true)
  })
})

describe('validateSignup', () => {
  it('올바른 입력은 오류 없음', () => {
    const errors = validateSignup(valid)
    expect(errors).toEqual({})
    expect(hasErrors(errors)).toBe(false)
  })

  it('이메일 앞뒤 공백은 허용한다 (서버가 trim)', () => {
    expect(validateSignup({ ...valid, email: '  user@example.com ' })).toEqual({})
  })

  it.each([
    ['', '이메일을 입력해 주세요'],
    ['user', '이메일 형식이 맞지 않습니다'],
    ['user@example', '이메일 형식이 맞지 않습니다'],
    ['us er@example.com', '이메일 형식이 맞지 않습니다'],
    ['@example.com', '이메일 형식이 맞지 않습니다'],
    [`${'a'.repeat(243)}@example.com`, '이메일은 254자 이하로 입력해 주세요'],
  ])('이메일 %j → %j', (email, message) => {
    expect(validateSignup({ ...valid, email }).email).toBe(message)
  })

  it('이메일 254자는 통과', () => {
    expect(validateSignup({ ...valid, email: `${'a'.repeat(242)}@example.com` }).email).toBeUndefined()
  })

  it('비밀번호 8자 미만은 오류, 8자는 통과', () => {
    expect(validateSignup({ ...valid, password: '1234567', passwordConfirm: '1234567' }).password).toBe(
      '비밀번호는 8자 이상이어야 합니다',
    )
    expect(validateSignup({ ...valid, password: '12345678', passwordConfirm: '12345678' })).toEqual({})
  })

  it('공백뿐인 비밀번호는 빈 값', () => {
    const blank = ' '.repeat(10)
    expect(validateSignup({ ...valid, password: blank, passwordConfirm: blank }).password).toBe(
      '비밀번호를 입력해 주세요',
    )
  })

  it('UTF-8 72바이트까지 통과: 영문 72자, 한글 24자', () => {
    for (const password of ['a'.repeat(72), '가'.repeat(24)]) {
      expect(validateSignup({ ...valid, password, passwordConfirm: password })).toEqual({})
    }
  })

  it('72바이트를 넘으면 오류: 영문 73자, 한글 25자', () => {
    for (const password of ['a'.repeat(73), '가'.repeat(25)]) {
      expect(validateSignup({ ...valid, password, passwordConfirm: password }).password).toMatch(/너무 깁니다/)
    }
  })

  it('비밀번호 확인이 다르면 확인 칸에 오류', () => {
    expect(validateSignup({ ...valid, passwordConfirm: 'password124' })).toEqual({
      passwordConfirm: '비밀번호가 서로 다릅니다',
    })
  })
})

describe('utf8Bytes', () => {
  it('영문 1바이트, 한글 3바이트, 이모지 4바이트', () => {
    expect(utf8Bytes('ab')).toBe(2)
    expect(utf8Bytes('가나')).toBe(6)
    expect(utf8Bytes('😀')).toBe(4)
  })
})

describe('authErrorMessage', () => {
  it.each([
    [new ApiError(401), 'login', LOGIN_FAILED_MESSAGE],
    [new ApiError(409), 'signup', DUPLICATE_EMAIL_MESSAGE],
    [new ApiError(400, { detail: 'Invalid request content.' }), 'signup', '입력값을 확인해 주세요'],
    [new ApiError(400), 'login', '입력값을 확인해 주세요'],
    [new ApiError(403), 'login', '보안 토큰이 만료됐습니다. 다시 시도해 주세요'],
    [new ApiError(500), 'signup', '잠시 후 다시 시도해 주세요'],
    [new TypeError('Failed to fetch'), 'login', '잠시 후 다시 시도해 주세요'],
  ] as const)('%o (%s) → %j', (error, action, message) => {
    expect(authErrorMessage(error, action)).toBe(message)
  })

  it('상태 코드의 뜻은 동작에 따라 다르다: 로그인의 409, 가입의 401은 일반 문구', () => {
    expect(authErrorMessage(new ApiError(409), 'login')).toBe('잠시 후 다시 시도해 주세요')
    expect(authErrorMessage(new ApiError(401), 'signup')).toBe('잠시 후 다시 시도해 주세요')
  })
})
