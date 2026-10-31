-- ADR 010 ⑧: 반경 검색용 공간 인덱스
-- 거리는 geography(미터, 타원체)로 잰다. 인덱스를 쓰려면 쿼리도 이 식과 똑같이 location::geography로 써야 한다
CREATE INDEX idx_apt_complex_location_geog ON apt_complex USING GIST ((location::geography));
