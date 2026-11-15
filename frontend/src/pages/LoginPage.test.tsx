import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from '../app/AppRoutes'
import { mockApi, type MockResponse } from '../test/mockApi'
import { renderWithProviders } from '../test/render'

const ME = { id: 1, email: 'user@example.com', role: 'USER' }
/** 로그인 뒤 돌아갈 곳. API를 부르지 않는 화면(없는 주소 → NotFoundPage)이라 mock이 필요 없다 */
const NEXT = '/somewhere?x=1'
const LOGIN = `/login?next=${encodeURIComponent(NEXT)}`

const location = () => screen.getByTestId('location')
const header = () => within(screen.getByRole('banner'))
const emailInput = () => screen.getByLabelText('이메일')
const passwordInput = () => screen.getByLabelText('비밀번호')
const submitButton = () => screen.getByRole('button', { name: '로그인' })

function withCsrfCookie() {
  document.cookie = 'XSRF-TOKEN=t; path=/'
}

async function fillAndSubmit(email: string, password: string) {
  if (email) await userEvent.type(emailInput(), email)
  if (password) await userEvent.type(passwordInput(), password)
  await userEvent.click(submitButton())
}

describe('LoginPage', () => {
  it('제목과 가입 링크(next 유지)', async () => {
    mockApi({})
    renderWithProviders(<AppRoutes />, { route: LOGIN })

    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(document.title).toBe('로그인 · Dream APT')
    expect(screen.getByRole('link', { name: '가입하기' })).toHaveAttribute(
      'href',
      `/signup?next=${encodeURIComponent(NEXT)}`,
    )
  })

  it('next가 없으면 가입 링크에도 붙이지 않는다', () => {
    mockApi({})
    renderWithProviders(<AppRoutes />, { route: '/login' })

    expect(screen.getByRole('link', { name: '가입하기' })).toHaveAttribute('href', '/signup')
  })

  it('빈 칸으로 제출하면 칸마다 문구를 보여 주고 요청하지 않는다. 고치기 시작하면 그 칸의 문구는 사라진다', async () => {
    const server = mockApi({})
    renderWithProviders(<AppRoutes />, { route: LOGIN })

    await userEvent.click(submitButton())

    expect(screen.getByText('이메일을 입력해 주세요')).toBeInTheDocument()
    expect(screen.getByText('비밀번호를 입력해 주세요')).toBeInTheDocument()
    expect(emailInput()).toHaveAttribute('aria-invalid', 'true')
    expect(emailInput()).toHaveAccessibleDescription('이메일을 입력해 주세요')
    expect(server.callsTo('POST', '/api/auth/login')).toHaveLength(0)

    await userEvent.type(emailInput(), 'a')
    expect(screen.queryByText('이메일을 입력해 주세요')).not.toBeInTheDocument()
    expect(screen.getByText('비밀번호를 입력해 주세요')).toBeInTheDocument()
  })

  it('성공하면 next로 이동하고, 헤더가 내 정보를 /api/me 재요청 없이 보여 준다', async () => {
    withCsrfCookie()
    const server = mockApi({ 'POST /api/auth/login': { body: ME } })
    renderWithProviders(<AppRoutes />, { route: LOGIN })

    await fillAndSubmit('  user@example.com ', 'password123')

    expect(await header().findByText('user@example.com')).toBeInTheDocument()
    expect(location()).toHaveTextContent(NEXT)
    const [call] = server.callsTo('POST', '/api/auth/login')
    expect(JSON.parse(call.body)).toEqual({ email: 'user@example.com', password: 'password123' })
    expect(server.to('/api/me')).toHaveLength(1) // 처음 화면을 열 때 한 번뿐
  })

  it('이동은 replace라 뒤로 가기로 로그인 화면에 돌아오지 않는다', async () => {
    withCsrfCookie()
    mockApi({ 'POST /api/auth/login': { body: ME }, '/api/regions': { body: [] } })
    renderWithProviders(<AppRoutes />, { entries: ['/', LOGIN] })

    await fillAndSubmit('user@example.com', 'password123')
    await screen.findByText('페이지를 찾을 수 없어요')
    await userEvent.click(screen.getByTestId('history-back'))

    expect(location()).toHaveTextContent(/^\/$/)
  })

  it.each(['//evil.com', 'https://evil.com', '/login'])('next=%s는 따르지 않고 첫 화면으로', async (next) => {
    withCsrfCookie()
    mockApi({ 'POST /api/auth/login': { body: ME }, '/api/regions': { body: [] } })
    renderWithProviders(<AppRoutes />, { route: `/login?next=${encodeURIComponent(next)}` })

    await fillAndSubmit('user@example.com', 'password123')

    expect(await screen.findByRole('heading', { name: '단지 검색' })).toBeInTheDocument()
    expect(location()).toHaveTextContent(/^\/$/)
  })

  it('401이면 문구를 보여 주고 화면에 남는다 (이메일 유지)', async () => {
    withCsrfCookie()
    mockApi({
      'POST /api/auth/login': {
        status: 401,
        body: { status: 401, detail: '이메일 또는 비밀번호가 맞지 않습니다' },
      },
    })
    renderWithProviders(<AppRoutes />, { route: LOGIN })

    await fillAndSubmit('user@example.com', 'wrong-password')

    expect(await screen.findByRole('alert')).toHaveTextContent('이메일 또는 비밀번호가 맞지 않습니다')
    expect(emailInput()).toHaveValue('user@example.com')
    expect(location()).toHaveTextContent('/login')
    expect(header().queryByText('user@example.com')).not.toBeInTheDocument()
  })

  it('요청 중에는 버튼을 막고 다시 눌러도 한 번만 보낸다', async () => {
    withCsrfCookie()
    let respond: (value: MockResponse) => void = () => {}
    const server = mockApi({ 'POST /api/auth/login': () => new Promise((resolve) => (respond = resolve)) })
    renderWithProviders(<AppRoutes />, { route: LOGIN })

    await fillAndSubmit('user@example.com', 'password123')
    const pending = await screen.findByRole('button', { name: '로그인 중…' })
    expect(pending).toBeDisabled()
    // Enter로 다시 제출해도 막힌다
    await userEvent.type(passwordInput(), '{Enter}')

    respond({ status: 500, body: { status: 500 } })
    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요')
    expect(server.callsTo('POST', '/api/auth/login')).toHaveLength(1)
    expect(submitButton()).toBeEnabled()
  })

  it('이미 로그인했으면 폼 없이 바로 next로', async () => {
    mockApi({ 'GET /api/me': { body: ME } })
    renderWithProviders(<AppRoutes />, { route: LOGIN })

    expect(await screen.findByText('페이지를 찾을 수 없어요')).toBeInTheDocument()
    expect(location()).toHaveTextContent(NEXT)
  })

  it('가입 직후(state)면 안내를 보여 주고 ?email=로 이메일 칸을 채운다', () => {
    mockApi({})
    renderWithProviders(<AppRoutes />, {
      route: { pathname: '/login', search: '?email=new%40example.com', state: { signedUp: true } },
    })

    expect(screen.getByRole('status')).toHaveTextContent('가입했습니다. 로그인해 주세요.')
    expect(emailInput()).toHaveValue('new@example.com')
    expect(passwordInput()).toHaveValue('')
  })

  it('state 없이 ?email=만 있으면 이메일만 채우고 안내는 없다 (공유한 링크)', () => {
    mockApi({})
    renderWithProviders(<AppRoutes />, { route: '/login?email=new%40example.com' })

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(emailInput()).toHaveValue('new@example.com')
  })
})
