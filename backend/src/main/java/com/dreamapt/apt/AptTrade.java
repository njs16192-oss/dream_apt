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
 * 아파트 매매 거래. 중복 판단 키: (apt_seq, deal_date, exclu_use_ar, floor, deal_amount)
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

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
