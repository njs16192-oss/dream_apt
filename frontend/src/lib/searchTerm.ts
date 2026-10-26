/** 끝에 붙은 자음·모음. 한글 조합 중("롯ㄷ", "헬리ㅇ", "ㄹ")에 멈췄을 때 아직 완성되지 않은 글자다. */
const TRAILING_JAMO = /[ㄱ-ㅎㅏ-ㅣ]+$/

/**
 * 검색 요청에 쓸 검색어. 요청하지 않을 입력이면 null.
 * - 끝에 붙은 자모는 뗀다. 조합 중에 멈춰도 "롯ㄷ" 대신 "롯"으로 검색해서 0건 화면이 깜빡이지 않는다
 * - 앞뒤 공백은 뗀다 ("헬리오 "와 "헬리오"가 같은 캐시를 쓰게)
 */
export function searchTerm(input: string): string | null {
  const term = input.trim().replace(TRAILING_JAMO, '').trim()
  return term === '' ? null : term
}
