/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 지도 타일 URL 템플릿. 없으면 OSM 타일 (ADR 010 ⑦) */
  readonly VITE_MAP_TILE_URL?: string
}
