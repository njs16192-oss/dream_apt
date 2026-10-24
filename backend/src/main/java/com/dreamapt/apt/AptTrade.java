package com.dreamapt.apt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 아파트 매매 거래. 같은 그룹 키 (apt_seq, deal_date, exclu_use_ar, floor, deal_amount)의
 * 행이 여러 개일 수 있고, 수집할 때 구×월 단위로 원본과 대조한다 (ADR 004).
 * 유효 거래 = {@code cancelled = false AND missing_since IS NULL}
 */
@Entity
@Table(name = "apt_trade")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AptTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "apt_seq", nullable = false)
    private AptComplex complex;

    @Column(nullable = false)
    private LocalDate dealDate;

    // 단위: 만원
    @Column(nullable = false)
    private Integer dealAmount;

    // 전용면적(㎡)
    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal excluUseAr;

    @Column(nullable = false)
    private Short floor;

    @Column(length = 20)
    private String aptDong;

    @Column(length = 10)
    private String buyerGbn;

    @Column(length = 10)
    private String sellerGbn;

    @Column(length = 10)
    private String dealingGbn;

    @Column(length = 50)
    private String agentSggNm;

    // 해제 거래. 가격 추이·신고가 판단에서는 기본 제외
    @Column(nullable = false)
    private boolean cancelled;

    private LocalDate cancelDate;

    private LocalDate registeredDate;

    // 원본(API)에서 사라진 것이 처음 확인된 시각. 다시 나타나면 null
    private LocalDateTime missingSince;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
