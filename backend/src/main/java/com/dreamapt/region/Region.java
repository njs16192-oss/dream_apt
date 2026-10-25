package com.dreamapt.region;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시군구. 수집 대상 구는 collectEnabled로 정한다 (ADR 005). 데이터는 마이그레이션으로 넣는다.
 */
@Entity
@Table(name = "region")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 5)
    private String sggCd;

    @Column(nullable = false, length = 20)
    private String sidoNm;

    @Column(nullable = false, length = 20)
    private String sggNm;

    @Column(nullable = false)
    private boolean collectEnabled;
}
