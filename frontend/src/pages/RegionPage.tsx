import { useParams } from 'react-router'

export function RegionPage() {
  const { sggCd } = useParams()
  return <h1 className="text-xl font-bold">구 거래량 {sggCd}</h1>
}
