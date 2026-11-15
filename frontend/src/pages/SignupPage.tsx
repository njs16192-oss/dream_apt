import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { useSignup } from '../api/auth'
import { ApiError } from '../api/client'
import { meQuery } from '../api/queries'
import { FormField } from '../components/FormField'
import {
  authErrorMessage,
  hasErrors,
  PASSWORD_MAX_BYTES,
  PASSWORD_MIN_LENGTH,
  validateSignup,
  type FieldErrors,
  type SignupForm,
} from '../lib/authForm'
import { safeNext } from '../lib/safeNext'
import { useDocumentTitle } from '../lib/useDocumentTitle'
import type { LoginLocationState } from './LoginPage'

/** 로그인 화면 주소. email은 이메일 칸 초기값, next는 '/'가 아닐 때만 싣는다 */
function loginPath(next: string, email?: string): string {
  const params = new URLSearchParams()
  if (email) params.set('email', email)
  if (next !== '/') params.set('next', next)
  const query = params.toString()
  return query ? `/login?${query}` : '/login'
}

/**
 * 가입. 서버는 가입만 하고 로그인하지 않는다.
 * 성공하면 로그인 화면으로 replace 이동한다: 이메일 칸을 채우고(?email=), "가입했습니다" 안내는 라우터 state로 넘긴다.
 * ?next=는 로그인 화면으로 그대로 넘겨, 로그인한 뒤 원래 화면으로 돌아가게 한다.
 */
export function SignupPage() {
  useDocumentTitle('가입')
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const next = safeNext(searchParams.get('next'))

  const me = useQuery(meQuery)
  const signup = useSignup()
  const [form, setForm] = useState<SignupForm>({ email: '', password: '', passwordConfirm: '' })
  const [errors, setErrors] = useState<FieldErrors<SignupForm>>({})

  if (me.data) return <Navigate to={next} replace />

  const change = (field: keyof SignupForm) => (value: string) => {
    setForm((previous) => ({ ...previous, [field]: value }))
    setErrors((previous) => ({ ...previous, [field]: undefined }))
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const found = validateSignup(form)
    setErrors(found)
    if (hasErrors(found) || signup.isPending) return
    const email = form.email.trim()
    signup.mutate(
      { email, password: form.password },
      {
        onSuccess: (created) => {
          const state: LoginLocationState = { signedUp: true }
          navigate(loginPath(next, created.email), { replace: true, state })
        },
      },
    )
  }

  const duplicate = signup.error instanceof ApiError && signup.error.status === 409

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="text-2xl font-bold text-slate-900">가입</h1>
      <form noValidate onSubmit={submit} className="mt-6 space-y-4">
        <FormField
          label="이메일"
          type="email"
          autoComplete="email"
          value={form.email}
          onChange={change('email')}
          error={errors.email}
        />
        <FormField
          label="비밀번호"
          type="password"
          autoComplete="new-password"
          value={form.password}
          onChange={change('password')}
          error={errors.password}
          hint={`${PASSWORD_MIN_LENGTH}자 이상 (영문 ${PASSWORD_MAX_BYTES}자, 한글 ${PASSWORD_MAX_BYTES / 3}자까지)`}
        />
        <FormField
          label="비밀번호 확인"
          type="password"
          autoComplete="new-password"
          value={form.passwordConfirm}
          onChange={change('passwordConfirm')}
          error={errors.passwordConfirm}
        />
        {signup.isError && (
          <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">
            {authErrorMessage(signup.error, 'signup')}
            {duplicate && (
              <>
                {' '}
                <Link
                  to={loginPath(next, form.email.trim())}
                  className="font-medium underline underline-offset-2"
                >
                  로그인하기
                </Link>
              </>
            )}
          </p>
        )}
        <p className="text-xs text-slate-500">비밀번호를 잊으면 아직 되찾을 수 없어요. 잘 기억해 두세요.</p>
        <button
          type="submit"
          disabled={signup.isPending}
          className="w-full rounded-lg bg-blue-700 px-4 py-2.5 font-medium text-white hover:bg-blue-800 disabled:opacity-50"
        >
          {signup.isPending ? '가입 중…' : '가입'}
        </button>
      </form>
      <p className="mt-6 text-center text-sm text-slate-600">
        이미 계정이 있나요?{' '}
        <Link to={loginPath(next)} className="font-medium text-blue-700 underline-offset-2 hover:underline">
          로그인
        </Link>
      </p>
    </div>
  )
}
