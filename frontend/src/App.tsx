import { useEffect, useState } from 'react'

function App() {
  const [msg, setMsg] = useState('연결 중...')

  useEffect(() => {
    fetch('/api/ping')
      .then((res) => res.text())
      .then(setMsg)
      .catch(() => setMsg('연결 실패'))
  }, [])

  return <h1>Dream APT · 서버 응답: {msg}</h1>
}

export default App