import { ApiError } from '../api/client'

/** 서버 SignupRequest와 같은 제한 (@Size, ADR 011 ③) */
export const PASSWORD_MIN_LENGTH = 8
/** BCrypt는 72바이트까지만 쓴다. 서버도 UTF-8 기준 72바이트를 넘으면 400이다 (SignupService) */
export const PASSWORD_MAX_BYTES = 72
export const EMAIL_MAX_LENGTH = 254

/** 서버와 같은 문구. 없는 이메일과 틀린 비밀번호를 나누지 않는다 (ADR 011 ①) */
export const LOGIN_FAILED_MESSAGE = '이메일 또는 비밀번호가 맞지 않습니다'
export const DUPLICATE_EMAIL_MESSAGE = '이미 가입된 이메일입니다'

/** 화면 검사용 대략적인 모양 (x@y.z). 정확한 판단은 서버가 한다 */
const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export type LoginForm = { email: string; password: string }
export type SignupForm = LoginForm & { passwordConfirm: string }

/** 필드별 오류 문구. 오류가 없는 필드는 키가 없다 */
export type FieldErrors<F> = Partial<Record<keyof F, string>>

export function hasErrors(errors: FieldErrors<object>): boolean {
  return Object.keys(errors).length > 0
}

/** 비밀번호를 UTF-8로 바꿨을 때의 바이트 수. 한글은 한 글자에 3바이트다 */
export function utf8Bytes(text: string): number {
  return new TextEncoder().encode(text).length
}

/** 서버 @NotBlank와 같다: 공백만 있으면 빈 값 */
const isBlank = (value: string) => value.trim() === ''

/** 로그인은 형식을 검사하지 않는다 (서버 LoginRequest와 같음). 틀리면 그냥 로그인 실패다 */
export function validateLogin(form: LoginForm): FieldErrors<LoginForm> {
  const errors: FieldErrors<LoginForm> = {}
  if (isBlank(form.email)) errors.email = '이메일을 입력해 주세요'
  if (isBlank(form.password)) errors.password = '비밀번호를 입력해 주세요'
  return errors
}

export function validateSignup(form: SignupForm): FieldErrors<SignupForm> {
  const errors: FieldErrors<SignupForm> = {}
  const email = form.email.trim()
  if (email === '') errors.email = '이메일을 입력해 주세요'
  else if (email.length > EMAIL_MAX_LENGTH) errors.email = `이메일은 ${EMAIL_MAX_LENGTH}자 이하로 입력해 주세요`
  else if (!EMAIL_SHAPE.test(email)) errors.email = '이메일 형식이 맞지 않습니다'

  if (isBlank(form.password)) errors.password = '비밀번호를 입력해 주세요'
  else if (form.password.length < PASSWORD_MIN_LENGTH)
    errors.password = `비밀번호는 ${PASSWORD_MIN_LENGTH}자 이상이어야 합니다`
  else if (utf8Bytes(form.password) > PASSWORD_MAX_BYTES)
    errors.password = `비밀번호가 너무 깁니다 (영문 ${PASSWORD_MAX_BYTES}자, 한글 ${PASSWORD_MAX_BYTES / 3}자까지)`

  if (form.passwordConfirm !== form.password) errors.passwordConfirm = '비밀번호가 서로 다릅니다'
  return errors
}

/**
 * 로그인·가입 요청이 실패했을 때 보여 줄 문구.
 * 서버의 400 detail은 대부분 Spring 기본 영어 문구라 쓰지 않는다. 화면 검사가 먼저 막으므로 고정 문구로 둔다.
 */
export function authErrorMessage(error: unknown, action: 'login' | 'signup'): string {
  if (error instanceof ApiError) {
    if (action === 'login' && error.status === 401) return LOGIN_FAILED_MESSAGE
    if (action === 'signup' && error.status === 409) return DUPLICATE_EMAIL_MESSAGE
    if (error.status === 400) return '입력값을 확인해 주세요'
    // CSRF 토큰이 없거나 맞지 않을 때. 다시 보내면 미들웨어가 새 토큰을 받아 온다
    if (error.status === 403) return '보안 토큰이 만료됐습니다. 다시 시도해 주세요'
  }
  return '잠시 후 다시 시도해 주세요'
}
