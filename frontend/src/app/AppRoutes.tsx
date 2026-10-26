import { Route, Routes } from 'react-router'
import { Layout } from '../components/Layout'
import { ComplexPage } from '../pages/ComplexPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { RegionPage } from '../pages/RegionPage'
import { SearchPage } from '../pages/SearchPage'

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<SearchPage />} />
        <Route path="complexes/:aptSeq" element={<ComplexPage />} />
        <Route path="regions/:sggCd" element={<RegionPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
