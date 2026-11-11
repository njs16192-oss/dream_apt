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
     * 판단·기록·알림 생성이 한 문장이라 그 자체로 원자적이다. 트랜잭션은 이 메서드에 일을 더할 때를 위해 둔다
     * (step은 ResourcelessTransactionManager라 여기서 트랜잭션을 연다, ADR 011 5. 구현 기록).
     */
    @Transactional
    public NewHighDetectResult detect(LocalDate baseDate) {
        LocalDate from = baseDate.minusMonths(DETECT_MONTHS);
        NewHighEventRepository.RecordResult result = repository.recordSince(from);
        log.info("신고가 판단 baseDate={} from={} 새 기록={} 새 알림={}",
                baseDate, from, result.recorded(), result.notified());
        return new NewHighDetectResult(from, result.recorded(), result.notified());
    }
}
