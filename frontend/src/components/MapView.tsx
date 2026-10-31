import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { useEffect, useRef } from 'react'
import type { Schemas } from '../api/client'
import { formatDistance } from '../lib/format'
import { CHART } from '../lib/chartTheme'

const TILE_URL = import.meta.env.VITE_MAP_TILE_URL ?? 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
// OSM 타일은 출처 표시가 필수이고, 단지 좌표는 행정안전부 데이터(공공누리 제1유형)라 함께 밝힌다 (ADR 010 ⑦)
const ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors | 주소·좌표: 행정안전부'

type Props = {
  center: { lat: number; lng: number }
  /** 반경 (m) */
  radius: number
  items: readonly Schemas['NearbyComplex'][]
  /** 지도 빈 곳(반경 원 안 포함)을 누르면 그 위치 */
  onCenterChange: (lat: number, lng: number) => void
  /** 단지 마커를 누르면. 이동은 페이지가 React Router로 한다 (location.href로 가면 앱을 다시 불러온다) */
  onSelect: (aptSeq: string) => void
}

/**
 * Leaflet 지도. 마커가 최대 수백 개라 canvas로 그린다 (preferCanvas).
 * 지도는 한 번 만들고 반경 원·마커만 바꾼다. StrictMode에서 effect가 두 번 돌아도 cleanup의 remove()로
 * "Map container is already initialized"를 막는다. Leaflet이 pane에 z-index를 쓰므로 부모가 isolate로 가둔다.
 */
export function MapView({ center, radius, items, onCenterChange, onSelect }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const areaRef = useRef<L.LayerGroup | null>(null)
  const markersRef = useRef<L.LayerGroup | null>(null)
  // 이벤트는 지도를 만들 때 한 번 건다. 콜백은 최신 것을 ref로 읽는다
  const onCenterChangeRef = useRef(onCenterChange)
  const onSelectRef = useRef(onSelect)
  useEffect(() => {
    onCenterChangeRef.current = onCenterChange
    onSelectRef.current = onSelect
  })

  useEffect(() => {
    const map = L.map(containerRef.current!, { preferCanvas: true })
    L.tileLayer(TILE_URL, { attribution: ATTRIBUTION, maxZoom: 19 }).addTo(map)
    map.on('click', (event: L.LeafletMouseEvent) => onCenterChangeRef.current(event.latlng.lat, event.latlng.lng))
    areaRef.current = L.layerGroup().addTo(map)
    markersRef.current = L.layerGroup().addTo(map)
    mapRef.current = map
    return () => {
      map.remove()
      mapRef.current = null
      areaRef.current = null
      markersRef.current = null
    }
  }, [])

  // 반경 원과 중심점. 둘 다 클릭 대상이 아니다(interactive: false): 원 안쪽을 눌러도 지도 클릭으로 가서 중심이 옮겨진다
  useEffect(() => {
    const map = mapRef.current!
    const area = areaRef.current!
    area.clearLayers()
    // 처음에는 지도에 아직 보기 영역이 없어 원이 투영되지 않는다. 그래서 circle.getBounds() 대신
    // 중심에서 지름만큼의 사각형으로 맞춘다 (원을 꼭 감싸는 크기다)
    map.fitBounds(L.latLng(center.lat, center.lng).toBounds(radius * 2), { padding: [12, 12] })
    L.circle([center.lat, center.lng], {
      radius,
      interactive: false,
      color: CHART.primary,
      weight: 1.5,
      fillOpacity: 0.06,
    }).addTo(area)
    L.circleMarker([center.lat, center.lng], {
      radius: 4,
      interactive: false,
      color: '#0f172a',
      weight: 2,
      fillColor: '#ffffff',
      fillOpacity: 1,
    }).addTo(area)
  }, [center.lat, center.lng, radius])

  // 단지 마커. 누르면 상세로 간다. bubblingMouseEvents: false라 같은 클릭이 지도 클릭(중심 이동)으로 번지지 않는다
  useEffect(() => {
    const markers = markersRef.current!
    markers.clearLayers()
    for (const item of items) {
      L.circleMarker([item.lat, item.lng], {
        radius: 6,
        bubblingMouseEvents: false,
        color: '#ffffff',
        weight: 1,
        fillColor: CHART.primary,
        fillOpacity: 0.9,
      })
        .bindTooltip(`${item.name} · ${formatDistance(item.distanceM)}`)
        .on('click', () => onSelectRef.current(item.aptSeq))
        .addTo(markers)
    }
  }, [items])

  return <div ref={containerRef} role="region" aria-label="단지 지도" className="h-72 w-full sm:h-[420px]" />
}
