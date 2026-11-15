import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from '../app/AppRoutes'
import { mockApi, type MockResponse } from '../test/mockApi'
import { renderWithProviders } from '../test/render'

const NEXT = '/somewhere?x=1'
const SIGNUP = `/signup?next=${encodeURIComponent(NEXT)}`

const location = () => screen.getByTestId('location')
const emailInput = () => screen.getByLabelText('이메일')
const passwordInput = () => screen.getByLabelText('비밀번호')
const confirmInput = () => screen.getByLabelText('비밀번호 확인')
const submitButton = () => screen.getByRole('button', { name: '가입' })

function withCsrfCookie() {
  document.cookie = 'XSRF-TOKEN=t; path=/'
}

async function fillAndSubmit(email: string, password: string, confirm = password) {
  if (email) await userEvent.type(emailInput(), email)
  if (password) await userEvent.type(passwordInput(), password)
  if (confirm) await userEvent.type(confirmInput(), confirm)
  await userEvent.click(submitButton())
}

describe('SignupPage', () => {
  it('제목, 비밀번호 규칙 안내, 로그인 링크(next 유지)', () => {
    mockApi({})
    renderWithProviders(<AppRoutes />, { route: SIGNUP })

    expect(screen.getByRole('heading', { name: '가입' })).toBeInTheDocument()
    expect(document.title).toBe('가입 · Dream APT')
    expect(passwordInput()).toHaveAccessibleDescription('8자 이상 (영문 72자, 한글 24자까지)')
    expect(screen.getByRole('link', { name: '로그인' })).toHaveAttribute(
      'href',
      `/login?next=${encodeURIComponent(NEXT)}`,
    )
  })

  it('next가 없으면 로그인 링크에도 붙이지 않는다', () => {
    mockApi({})
    renderWithProviders(<AppRoutes />, { route: '/signup' })

    expect(screen.getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login')
  })

  it.each([
    ['', '', '', ['이메일을 입력해 주세요', '비밀번호를 입력해 주세요']],
    ['user@example', 'password123', 'password123', ['이메일 형식이 맞지 않습니다']],
    ['user@example.com', '1234567', '1234567', ['비밀번호는 8자 이상이어야 합니다']],
    ['user@example.com', '가'.repeat(25), '가'.repeat(25), ['비밀번호가 너무 깁니다 (영문 72자, 한글 24자까지)']],
    ['user@example.com', 'password123', 'password124', ['비밀번호가 서로 다릅니다']],
  ])('화면 검사: %j / %j / %j → 문구, 요청하지 않음', async (email, password, confirm, messages) => {
    const server = mockApi({})
    renderWithProviders(<AppRoutes />, { route: SIGNUP })

    await fillAndSubmit(email, password, confirm)

    for (const message of messages) expect(screen.getByText(message)).toBeInTheDocument()
    expect(server.callsTo('POST', '/api/auth/signup')).toHaveLength(0)
  })

  it('비밀번호 오류가 있으면 규칙 안내 대신 오류를 칸 설명으로', async () => {
    mockApi({})
    renderWithProviders(<AppRoutes />, { route: SIGNUP })

    await fillAndSubmit('user@example.com', 'short', 'short')

    expect(passwordInput()).toHaveAttribute('aria-invalid', 'true')
    expect(passwordInput()).toHaveAccessibleDescription('비밀번호는 8자 이상이어야 합니다')
    expect(screen.queryByText(/8자 이상 \(영문/)).not.toBeInTheDocument()
  })

  it('성공하면 로그인 화면으로: 서버가 돌려준 이메일로 칸을 채우고, 가입 안내와 next를 넘긴다. 로그인은 하지 않는다', async () => {
    withCsrfCookie()
    const server = mockApi({ 'POST /api/auth/signup': { status: 201, body: { id: 7, email: 'new@example.com' } } })
    renderWithProviders(<AppRoutes />, { route: SIGNUP })

    await fillAndSubmit('New@Example.com', 'password123')

    expect(await screen.findByRole('status')).toHaveTextContent('가입했습니다. 로그인해 주세요.')
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(emailInput()).toHaveValue('new@example.com')
    expect(location()).toHaveTextContent(
      `/login?email=new%40example.com&next=${encodeURIComponent(NEXT)}`,
    )
    const [call] = server.callsTo('POST', '/api/auth/signup')
    expect(JSON.parse(call.body)).toEqual({ email: 'New@Example.com', password: 'password123' })
    expect(server.callsTo('POST', '/api/auth/login')).toHaveLength(0)
  })

  it('이동은 replace라 뒤로 가기로 가입 화면에 돌아오지 않는다', async () => {
    withCsrfCookie()
    mockApi({
      'POST /api/auth/signup': { status: 201, body: { id: 7, email: 'new@example.com' } },
      '/api/regions': { body: [] },
    })
    renderWithProviders(<AppRoutes />, { entries: ['/', '/signup'] })

    await fillAndSubmit('new@example.com', 'password123')
    await screen.findByRole('status')
    await userEvent.click(screen.getByTestId('history-back'))

    expect(location()).toHaveTextContent(/^\/$/)
  })

  it('409면 문구와, 이메일·next를 실은 로그인 링크', async () => {
    withCsrfCookie()
    mockApi({
      'POST /api/auth/signup': { status: 409, body: { status: 409, detail: '이미 가입된 이메일입니다' } },
    })
    renderWithProviders(<AppRoutes />, { route: SIGNUP })

    await fillAndSubmit('user@example.com', 'password123')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('이미 가입된 이메일입니다')
    expect(screen.getByRole('link', { name: '로그인하기' })).toHaveAttribute(
      'href',
      `/login?email=user%40example.com&next=${encodeURIComponent(NEXT)}`,
    )
    expect(location()).toHaveTextContent('/signup')
  })

  it('400이면 고정 문구, 로그인 링크 없음', async () => {
    withCsrfCookie()
    mockApi({ 'POST /api/auth/signup': { status: 400, body: { status: 400, detail: 'Invalid request content.' } } })
    renderWithProviders(<AppRoutes />, { route: SIGNUP })

    await fillAndSubmit('user@example.com', 'password123')

    expect(await screen.findByRole('alert')).toHaveTextContent('입력값을 확인해 주세요')
    expect(screen.queryByRole('link', { name: '로그인하기' })).not.toBeInTheDocument()
  })

  it('요청 중에는 버튼을 막는다', async () => {
    withCsrfCookie()
    let respond: (value: MockResponse) => void = () => {}
    const server = mockApi({ 'POST /api/auth/signup': () => new Promise((resolve) => (respond = resolve)) })
    renderWithProviders(<AppRoutes />, { route: SIGNUP })

    await fillAndSubmit('user@example.com', 'password123')
    expect(await screen.findByRole('button', { name: '가입 중…' })).toBeDisabled()

    respond({ status: 500, body: { status: 500 } })
    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요')
    expect(submitButton()).toBeEnabled()
    expect(server.callsTo('POST', '/api/auth/signup')).toHaveLength(1)
  })

  it('이미 로그인했어도 위험한 next(//evil.com)는 따르지 않고 첫 화면으로', async () => {
    mockApi({ 'GET /api/me': { body: { id: 1, email: 'user@example.com', role: 'USER' } }, '/api/regions': { body: [] } })
    renderWithProviders(<AppRoutes />, { route: `/signup?next=${encodeURIComponent('//evil.com')}` })

    expect(await screen.findByRole('heading', { name: '단지 검색' })).toBeInTheDocument()
    expect(location()).toHaveTextContent(/^\/$/)
  })

  it('가입 화면에서 넘기는 next도 검사한 값이다', () => {
    mockApi({})
    renderWithProviders(<AppRoutes />, { route: `/signup?next=${encodeURIComponent('https://evil.com')}` })

    expect(screen.getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login')
  })

  it('이미 로그인했으면 폼 없이 바로 next로', async () => {
    mockApi({ 'GET /api/me': { body: { id: 1, email: 'user@example.com', role: 'USER' } } })
    renderWithProviders(<AppRoutes />, { route: SIGNUP })

    expect(await screen.findByText('페이지를 찾을 수 없어요')).toBeInTheDocument()
    expect(location()).toHaveTextContent(NEXT)
  })
})
