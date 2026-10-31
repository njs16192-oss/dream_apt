package com.dreamapt.support;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;

/** 조회 테스트용 단지·거래 행을 DB에 직접 넣는다. */
public class TradeRows {

    private final JdbcTemplate jdbc;
    private int nextFloor = 1;

    public TradeRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.update("DELETE FROM apt_trade");
        jdbc.update("DELETE FROM apt_complex");
    }

    public void complex(String aptSeq, String name, String sggCd) {
        complex(aptSeq, name, sggCd, "가락동", 2018);
    }

    public void complex(String aptSeq, String name, String sggCd, String umdNm, Integer buildYear) {
        jdbc.update("""
                INSERT INTO apt_complex (apt_seq, name, sgg_cd, umd_cd, umd_nm, build_year, road_address)
                VALUES (?, ?, ?, '10100', ?, ?, '송파대로 345')
                """, aptSeq, name, sggCd, umdNm, buildYear);
    }

    /** 단지 좌표 (WGS84) */
    public void location(String aptSeq, double lat, double lng) {
        jdbc.update("UPDATE apt_complex SET location = ST_SetSRID(ST_MakePoint(?, ?), 4326) WHERE apt_seq = ?",
                lng, lat, aptSeq);
    }

    /** 중심에서 meters만큼, 방위 azimuthDegrees(북=0, 동=90) 방향에 단지 좌표를 둔다. 타원체 거리라 반경 검색과 같은 기준이다 */
    public void locationFrom(String aptSeq, double centerLat, double centerLng, double meters, double azimuthDegrees) {
        jdbc.update("""
                UPDATE apt_complex
                SET location = ST_Project(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, radians(?))::geometry
                WHERE apt_seq = ?
                """, centerLng, centerLat, meters, azimuthDegrees, aptSeq);
    }

    /** 기본값: 금액 100,000만원, 84.99㎡, 층은 넣을 때마다 1씩 늘어남, 유효 거래. */
    public Trade trade(String aptSeq, LocalDate dealDate) {
        return new Trade(aptSeq, dealDate);
    }

    /** 같은 단지·같은 날 거래를 count건 넣는다. */
    public void trades(String aptSeq, LocalDate dealDate, int count, int amount) {
        for (int i = 0; i < count; i++) {
            trade(aptSeq, dealDate).amount(amount).insert();
        }
    }

    public class Trade {

        private final String aptSeq;
        private final LocalDate dealDate;
        private int amount = 100_000;
        private String area = "84.99";
        private boolean cancelled;
        private boolean missing;

        private Trade(String aptSeq, LocalDate dealDate) {
            this.aptSeq = aptSeq;
            this.dealDate = dealDate;
        }

        public Trade amount(int amount) {
            this.amount = amount;
            return this;
        }

        public Trade area(String area) {
            this.area = area;
            return this;
        }

        public Trade cancelled() {
            this.cancelled = true;
            return this;
        }

        public Trade missing() {
            this.missing = true;
            return this;
        }

        public long insert() {
            return jdbc.queryForObject("""
                    INSERT INTO apt_trade (apt_seq, deal_date, deal_amount, exclu_use_ar, floor, cancelled, missing_since)
                    VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END)
                    RETURNING id
                    """, Long.class, aptSeq, dealDate, amount, new BigDecimal(area), nextFloor++, cancelled, missing);
        }
    }
}
