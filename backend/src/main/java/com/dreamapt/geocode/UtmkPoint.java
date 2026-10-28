package com.dreamapt.geocode;

/**
 * 좌표제공 API가 주는 주출입구 좌표. UTM-K(EPSG:5179, GRS80), 단위 m. 저장할 때 PostGIS로 WGS84로 바꾼다.
 */
public record UtmkPoint(double x, double y) {

    public static final int SRID = 5179;
}
