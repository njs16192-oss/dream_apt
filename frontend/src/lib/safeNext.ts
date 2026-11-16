/** 로그인·가입 화면. 로그인한 뒤 여기로 돌아가면 다시 로그인 화면이 되므로 첫 화면으로 보낸다 */
const AUTH_PATHS = new Set(['/login', '/signup'])

/**
 * 로그인 뒤 돌아갈 곳(?next=). 우리 사이트 안의 경로일 때만 쓰고, 아니면 첫 화면('/').
 * 아무 값이나 따르면 "우리 로그인 화면을 거쳐 다른 사이트로 보내는 링크"(오픈 리다이렉트)가 만들어진다.
 * - '//evil.com', '/\evil.com'은 '/'로 시작해도 브라우저가 다른 사이트 주소로 읽는다
 * - 브라우저는 주소 안의 탭·줄바꿈을 지우므로 '/\t/evil.com'도 '//evil.com'이 된다
 * 그래서 글자로 따지지 않고 브라우저와 같은 규칙(URL)으로 풀어 본 뒤 출처(origin)가 같은지 확인한다.
 */
export function safeNext(next: string | null | undefined): string {
  if (!next || !next.startsWith('/')) return '/'
  const origin = globalThis.location.origin
  let url: URL
  try {
    url = new URL(next, origin)
  } catch {
    return '/'
  }
  if (url.origin !== origin || AUTH_PATHS.has(url.pathname)) return '/'
  return url.pathname + url.search + url.hash
}

/**
 * 로그인한 뒤 here로 돌아오는 로그인 화면 주소. here는 pathname + search (예: '/complexes/11710-1?area=85').
 * 로그인 화면은 받은 next를 safeNext로 다시 검사한다.
 */
export function loginPath(here: string): string {
  return `/login?next=${encodeURIComponent(here)}`
}
