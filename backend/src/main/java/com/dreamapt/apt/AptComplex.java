package com.dreamapt.apt;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 아파트 단지. 좌표(location)와 지오코딩 기록 컬럼은 매핑하지 않고 JDBC로만 다룬다 (ADR 010).
 */
@Entity
@Table(name = "apt_complex")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AptComplex {

    @Id
    @Column(length = 20)
    private String aptSeq;

    @Column(nullable = false, length = 100)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 5)
    private String sggCd;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 5)
    private String umdCd;

    @Column(length = 50)
    private String umdNm;

    @Column(length = 20)
    private String jibun;

    @Column(length = 200)
    private String roadAddress;

    private Short buildYear;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;
}
