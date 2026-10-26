import { useLocation, useNavigate } from 'react-router'

/** 현재 주소를 보여주고 뒤로 가기 버튼을 둔다. 테스트에서 URL 확인·바깥에서의 URL 변경에 쓴다. */
export function RouterProbe() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <div hidden>
      <output data-testid="location">{location.pathname + location.search}</output>
      <button type="button" data-testid="history-back" onClick={() => navigate(-1)} />
    </div>
  )
}
