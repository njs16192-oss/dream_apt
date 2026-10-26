/** 자음·모음(과 공백)만으로 된 값. 한글 조합 중("ㄹ", "ㅎㅔ")에 멈췄을 때의 입력이다. */
const JAMO_ONLY = /^[ㄱ-ㅎㅏ-ㅣ\s]+$/

/**
 * 검색 요청에 쓸 검색어. 요청하지 않을 입력이면 null.
 * - 앞뒤 공백은 뗀다 ("헬리오 "와 "헬리오"가 같은 캐시를 쓰게)
 * - 자모만으로 된 값은 조합 중인 글자라 요청하지 않는다 (0건 화면이 잠깐 깜빡이는 것을 막음)
 */
export function searchTerm(input: string): string | null {
  const term = input.trim()
  if (term === '' || JAMO_ONLY.test(term)) return null
  return term
}
