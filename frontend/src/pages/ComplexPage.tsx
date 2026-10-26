import { useParams } from 'react-router'

export function ComplexPage() {
  const { aptSeq } = useParams()
  return <h1 className="text-xl font-bold">단지 {aptSeq}</h1>
}
