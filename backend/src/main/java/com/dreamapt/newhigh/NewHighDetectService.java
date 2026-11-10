package com.dreamapt.newhigh;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 신고가 판단 (ADR 011 ⑤). 수집 Job의 지오코딩 step 뒤 step에서 부른다.
 * 기준일로부터 3개월 전(포함) 이후 계약 거래를 판단한다. 거래 신고 기한이 계약 후 30일이라 새 거래는 대부분 이 안에 들어온다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewHighDetectService {

    static final int DETECT_MONTHS = 3;

    private final NewHighEventRepository repository;

    /**
     * 지금은 INSERT 한 문장이지만 트랜잭션을 미리 연다. PR 7에서 새 신고가로 알림을 만드는 일이
     * 같은 트랜잭션에 들어가야 기록과 알림이 함께 커밋되거나 함께 롤백된다.
     */
    @Transactional
    public NewHighDetectResult detect(LocalDate baseDate) {
        LocalDate from = baseDate.minusMonths(DETECT_MONTHS);
        int recorded = repository.recordSince(from);
        log.info("신고가 판단 baseDate={} from={} 새 기록={}", baseDate, from, recorded);
        return new NewHighDetectResult(from, recorded);
    }
}
