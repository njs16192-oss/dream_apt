import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useSearchParams } from 'react-router'
import { useLogin } from '../api/auth'
import { meQuery } from '../api/queries'
import { FormField } from '../components/FormField'
import { authErrorMessage, hasErrors, validateLogin, type FieldErrors, type LoginForm } from '../lib/authForm'
import { safeNext } from '../lib/safeNext'
import { useDocumentTitle } from '../lib/useDocumentTitle'

/** 가입 화면이 성공 뒤 navigate의 state로 넘긴다. 주소에 두지 않아 공유한 링크에는 안내가 남지 않는다 */
export type LoginLocationState = { signedUp?: boolean } | null

/**
 * 로그인. ?next=는 로그인 뒤 돌아갈 곳(safeNext로 검사), ?email=은 이메일 칸 초기값(가입 직후).
 * 로그인에 성공하면 useLogin이 내 정보를 캐시에 넣고, 그 값이 생기면 아래 <Navigate>가 next로 보낸다.
 * 이미 로그인한 상태로 들어와도 같은 길로 바로 이동한다.
 */
export function LoginPage() {
  useDocumentTitle('로그인')
  const [searchParams] = useSearchParams()
  const location = useLocation()
  const next = safeNext(searchParams.get('next'))
  const signedUp = (location.state as LoginLocationState)?.signedUp === true

  const me = useQuery(meQuery)
  const login = useLogin()
  const [form, setForm] = useState<LoginForm>({ email: searchParams.get('email') ?? '', password: '' })
  const [errors, setErrors] = useState<FieldErrors<LoginForm>>({})

  if (me.data) return <Navigate to={next} replace />

  const change = (field: keyof LoginForm) => (value: string) => {
    setForm((previous) => ({ ...previous, [field]: value }))
    // 고치기 시작한 칸의 오류는 지운다. 다시 검사는 제출 때
    setErrors((previous) => ({ ...previous, [field]: undefined }))
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const found = validateLogin(form)
    setErrors(found)
    if (hasErrors(found) || login.isPending) return
    login.mutate({ email: form.email.trim(), password: form.password })
  }

  const signupLink = next === '/' ? '/signup' : `/signup?next=${encodeURIComponent(next)}`

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="text-2xl font-bold text-slate-900">로그인</h1>
      {signedUp && (
        <p role="status" className="mt-4 rounded-lg bg-green-50 px-3 py-2 text-sm text-green-800">
          가입했습니다. 로그인해 주세요.
        </p>
      )}
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
          autoComplete="current-password"
          value={form.password}
          onChange={change('password')}
          error={errors.password}
        />
        {login.isError && (
          <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">
            {authErrorMessage(login.error, 'login')}
          </p>
        )}
        <button
          type="submit"
          disabled={login.isPending}
          className="w-full rounded-lg bg-blue-700 px-4 py-2.5 font-medium text-white hover:bg-blue-800 disabled:opacity-50"
        >
          {login.isPending ? '로그인 중…' : '로그인'}
        </button>
      </form>
      <p className="mt-6 text-center text-sm text-slate-600">
        계정이 없나요?{' '}
        <Link to={signupLink} className="font-medium text-blue-700 underline-offset-2 hover:underline">
          가입하기
        </Link>
      </p>
    </div>
  )
}
