import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router'
import { Layout } from '../components/Layout'
import { Loading } from '../components/Loading'
import { ComplexPage } from '../pages/ComplexPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { RegionPage } from '../pages/RegionPage'
import { SearchPage } from '../pages/SearchPage'

// 지도 화면은 Leaflet(+CSS)을 쓰므로 따로 나눠 불러온다. 다른 화면의 번들 크기에 들어가지 않는다
const MapPage = lazy(() => import('../pages/MapPage').then((module) => ({ default: module.MapPage })))

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<SearchPage />} />
        <Route path="complexes/:aptSeq" element={<ComplexPage />} />
        <Route path="regions/:sggCd" element={<RegionPage />} />
        <Route
          path="map"
          element={
            <Suspense fallback={<Loading label="지도 불러오는 중" />}>
              <MapPage />
            </Suspense>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
